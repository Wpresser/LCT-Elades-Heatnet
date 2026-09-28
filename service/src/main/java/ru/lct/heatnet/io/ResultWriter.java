package ru.lct.heatnet.io;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.solver.Net;
import ru.lct.heatnet.solver.VariantSummary;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Потоковая выгрузка результата режима в GeoJSON FeatureCollection (WGS 84), §7.
 * Узлы из входа пишутся исходными координатами, чтобы концы линий совпадали с ними точно.
 */
public final class ResultWriter {

    private final ObjectMapper mapper;

    public ResultWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(Path file, List<VariantSummary> variants) throws IOException {
        Projection proj = new Projection();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
             JsonGenerator g = mapper.getFactory().createGenerator(out, JsonEncoding.UTF8)) {
            // числа без экспоненты (266495758.0 вместо 2.66495758E8), цифры те же
            g.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            for (VariantSummary v : variants) {
                writeVariant(g, v, proj);
            }
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    private void writeVariant(JsonGenerator g, VariantSummary s, Projection proj) throws IOException {
        Net net = s.net;
        String vid = net.variantId;
        Map<Net.Node, Coordinate> ll = new IdentityHashMap<>();
        for (Net.Node n : net.nodes) {
            ll.put(n, n.inputLonLat != null ? n.inputLonLat : proj.toLonLat(n.xy.x, n.xy.y));
        }
        for (Net.Line l : net.lines) {
            g.writeStartObject();
            g.writeStringField("type", "Feature");
            g.writeObjectFieldStart("properties");
            g.writeStringField("id", l.id);
            g.writeStringField("object_type", "heat_network");
            g.writeStringField("variant_id", vid);
            writeNodeId(g, "start_node_id", l.a);
            writeNodeId(g, "end_node_id", l.b);
            plain(g, "flow_tph", l.flow);
            g.writeNumberField("diameter", l.dn);
            plain(g, "length", l.length());
            g.writeStringField("laying_method", l.special ? "special" : "base");
            g.writeNullField("depth_start");
            g.writeNullField("depth_end");
            plain(g, "cost", VariantSummary.lineCost(l));
            // минимальный ДУ по расходу: если diameter больше — ДУ повышен правилом предельной длины (§2.3)
            g.writeNumberField("diag_dn_by_flow",
                    ru.lct.heatnet.rules.DiameterTable.minByFlow(l.flow).map(r -> r.dn).orElse(l.dn));
            if (l.special) {
                plain(g, "diag_k_spec", l.kSpec);
                g.writeArrayFieldStart("diag_crossing");
                for (String c : l.crossing) {
                    g.writeString(c);
                }
                g.writeEndArray();
            }
            g.writeEndObject();
            g.writeObjectFieldStart("geometry");
            g.writeStringField("type", "LineString");
            g.writeArrayFieldStart("coordinates");
            for (int i = 0; i < l.coords.size(); i++) {
                Coordinate c;
                if (i == 0) {
                    c = ll.get(l.a);
                } else if (i == l.coords.size() - 1) {
                    c = ll.get(l.b);
                } else {
                    c = proj.toLonLat(l.coords.get(i).x, l.coords.get(i).y);
                }
                g.writeArray(new double[]{c.x, c.y}, 0, 2);
            }
            g.writeEndArray();
            g.writeEndObject();
            g.writeEndObject();
        }
        for (Net.Node n : net.nodes) {
            if (n.kind != Net.NodeKind.NEW_CHAMBER && n.kind != Net.NodeKind.TECH_NODE) {
                continue;
            }
            g.writeStartObject();
            g.writeStringField("type", "Feature");
            g.writeObjectFieldStart("properties");
            g.writeStringField("id", n.outId);
            g.writeStringField("variant_id", vid);
            if (n.kind == Net.NodeKind.NEW_CHAMBER) {
                g.writeStringField("object_type", "heat_chamber");
                g.writeNumberField("diameter", s.chamberDn.get(n)[0]);
                plain(g, "cost", s.chamberCosts.get(n));
                g.writeBooleanField("diag_tie_in", n.tieIn);
            } else {
                g.writeStringField("object_type", "technical_node");
            }
            g.writeEndObject();
            g.writeObjectFieldStart("geometry");
            g.writeStringField("type", "Point");
            Coordinate c = ll.get(n);
            g.writeFieldName("coordinates");
            g.writeArray(new double[]{c.x, c.y}, 0, 2);
            g.writeEndObject();
            g.writeEndObject();
        }
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeObjectFieldStart("properties");
        g.writeStringField("id", vid + "_summary");
        g.writeStringField("object_type", "variant_summary");
        g.writeStringField("variant_id", vid);
        g.writeNumberField("rank", s.rank);
        plain(g, "construction_cost", s.construction);
        plain(g, "chamber_construction_cost", s.chamberCost);
        g.writeNumberField("existing_chamber_tie_in_count", s.tieInCount);
        plain(g, "existing_chamber_tie_in_cost", s.tieInCost);
        plain(g, "unconnected_penalty", s.penalty);
        plain(g, "calculated_cost", s.calculated);
        plain(g, "new_network_length", s.length);
        plain(g, "score", s.score);
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (Target t : s.unconnected) {
            t.id.write(g);
        }
        g.writeEndArray();
        g.writeStringField("diag_description", net.description);
        g.writeStringField("diag_terminal_policy", ru.lct.heatnet.routing.TerminalPolicy.current().code());
        g.writeBooleanField("diag_search_truncated", net.searchTruncated);
        g.writeArrayFieldStart("diag_relaxed_final_approach");
        for (Target t : net.relaxedApproach) {
            t.id.write(g);
        }
        g.writeEndArray();
        g.writeObjectFieldStart("diag_unconnected_reasons");
        for (Map.Entry<Target, String> e : net.unconnected.entrySet()) {
            g.writeStringField(e.getKey().id.toString(), e.getValue());
        }
        g.writeEndObject();
        g.writeEndObject();
        g.writeNullField("geometry");
        g.writeEndObject();
    }

    /** Число без экспоненты: BigDecimal из канонической записи double — те же цифры, что у Double.toString. */
    private static void plain(JsonGenerator g, String field, double v) throws IOException {
        g.writeFieldName(field);
        if (Double.isFinite(v)) {
            g.writeNumber(java.math.BigDecimal.valueOf(v));
        } else {
            g.writeNumber(v);
        }
    }

    private static void writeNodeId(JsonGenerator g, String field, Net.Node n) throws IOException {
        if (n.inputId != null) {
            n.inputId.writeField(g, field);
        } else {
            g.writeStringField(field, n.outId);
        }
    }
}
