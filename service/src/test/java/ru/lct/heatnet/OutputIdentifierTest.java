package ru.lct.heatnet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.io.InputLoader;
import ru.lct.heatnet.io.ResultWriter;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.solver.SolveService;
import ru.lct.heatnet.solver.Net;
import org.locationtech.jts.geom.Coordinate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OutputIdentifierTest {
    @TempDir Path temp;

    @Test
    void reservationsIncludeFilteredRestrictionsAndSurviveRollback() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(Paths.get("../scenarios/string_ids_two_in_one/input.geojson").toFile());
        String[] ids = {"v1_ch_1", "v1_ch_2", "v1_tn_1", "v1_net_1", "v1_summary", "v1_summary_1"};
        for (String id : ids) {
            ObjectNode feature = (ObjectNode) mapper.readTree("{\"type\":\"Feature\",\"properties\":{\"object_type\":\"restriction\",\"restriction_type\":\"park\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37,55],[37.001,55],[37.001,55.001],[37,55.001],[37,55]]]}}");
            ((ObjectNode) feature.path("properties")).put("id", id);
            ((com.fasterxml.jackson.databind.node.ArrayNode) input.path("features")).add(feature);
        }
        Path in = temp.resolve("filtered-ids.geojson");
        mapper.writeValue(in.toFile(), input);
        InputModel model = new InputLoader(mapper,new AppProperties().getLimits()).load(in);
        assertThat(model.reservedGeneratedIds).contains(ids);
        assertThat(model.restrictions).noneMatch(r -> r.id != null && model.reservedGeneratedIds.contains(r.id.toString()));
        Net net = new Net("v1", model.reservedGeneratedIds);
        assertThat(net.summaryId).isEqualTo("v1_summary_2");
        Net.Node a = net.newChamber(new Coordinate(0,0));
        Net.Node b = net.techNode(new Coordinate(0,10));
        assertThat(a.outId).isEqualTo("v1_ch_3");
        assertThat(b.outId).isEqualTo("v1_tn_2");
        Net.Snapshot before = net.snapshot();
        String allocated = net.addLine(a,b,java.util.Arrays.asList(a.xy,b.xy),false,1,java.util.Collections.emptyList()).id;
        assertThat(allocated).isEqualTo("v1_net_2");
        net.restore(before);
        assertThat(net.addLine(a,b,java.util.Arrays.asList(a.xy,b.xy),false,1,java.util.Collections.emptyList()).id).isEqualTo(allocated);
    }

    @Test
    void generatedIdentifiersNeverShadowArbitraryInputIdentifiers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String reserved : new String[]{"v1_ch_1", "v1_tn_1", "v1_net_1", "v1_summary", "v2_ch_1"}) {
            JsonNode input = mapper.readTree(Paths.get("../scenarios/string_ids_two_in_one/input.geojson").toFile());
            for (JsonNode feature : input.path("features")) {
                if ("a".equals(feature.path("properties").path("id").asText())) {
                    ((ObjectNode) feature.path("properties")).put("id", reserved);
                }
            }
            Path in = temp.resolve(reserved + "-input.geojson");
            mapper.writeValue(in.toFile(), input);
            InputModel model = new InputLoader(mapper, new AppProperties().getLimits()).load(in);
            Path out = temp.resolve(reserved + "-output.geojson");
            new ResultWriter(mapper).write(out, new SolveService().solve(model));
            Set<String> generated = new HashSet<>();
            for (JsonNode feature : mapper.readTree(out.toFile()).path("features")) {
                String id = feature.path("properties").path("id").asText();
                assertThat(id).as("new object must not shadow input %s", reserved).isNotEqualTo(reserved);
                assertThat(generated.add(id)).as("output ID must be unique: %s", id).isTrue();
            }
            assertThat(generated).isNotEmpty();
        }
    }
}
