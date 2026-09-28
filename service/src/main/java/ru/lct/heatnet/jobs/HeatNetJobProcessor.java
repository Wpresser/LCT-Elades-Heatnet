package ru.lct.heatnet.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.io.InputLoader;
import ru.lct.heatnet.io.ResultWriter;
import ru.lct.heatnet.model.Diagnostics;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.solver.SolveService;
import ru.lct.heatnet.solver.VariantSummary;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Расчёт задания: загрузка входа → варианты 2D → выгрузка GeoJSON. */
@Component
public class HeatNetJobProcessor implements JobProcessor {

    private final ObjectMapper mapper;
    private final AppProperties props;

    public HeatNetJobProcessor(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public Result process(Path input, Path jobDir) throws Exception {
        long t0 = System.nanoTime();
        InputLoader loader = new InputLoader(mapper, props.getLimits());
        InputModel model = loader.load(input);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        List<VariantSummary> variants = new SolveService().solve(model);
        double windowScale = 1.0;
        // есть неподключённые — повторяем с расширенными окнами поиска (ограничения догружаются), берём лучшее
        // точки без корректного flow_tph маршрута не получат ни при каком окне — ради них повтор не нужен
        if (variants.get(0).unconnected.size() > model.invalidTargets.size()) {
            InputModel wide = loader.load(input, WIDE_WINDOW_SCALE);
            List<VariantSummary> retry = new SolveService().solve(wide);
            if (better(retry.get(0), variants.get(0))) {
                model = wide;
                variants = retry;
                windowScale = WIDE_WINDOW_SCALE;
            }
        }
        long solveMs = (System.nanoTime() - t1) / 1_000_000;

        Path result = jobDir.resolve("result_2d.geojson");
        new ResultWriter(mapper).write(result, variants);

        ObjectNode stats = mapper.createObjectNode();
        stats.put("loadMs", loadMs);
        stats.put("windowScale", windowScale);
        stats.put("solveMs", solveMs);
        stats.put("heapUsedMb", usedHeapMb());
        stats.set("input", diagnosticsJson(model.diagnostics));
        ArrayNode vs = stats.putArray("variants");
        for (VariantSummary v : variants) {
            ObjectNode n = vs.addObject();
            n.put("variant_id", v.net.variantId);
            n.put("rank", v.rank);
            n.put("description", v.net.description);
            n.put("score", v.score);
            n.put("calculated_cost", v.calculated);
            n.put("new_network_length", v.length);
            n.put("connected", model.targets.size() + model.invalidTargets.size() - v.unconnected.size());
            n.put("unconnected", v.unconnected.size());
        }
        VariantSummary best = variants.get(0);
        int total = model.targets.size() + model.invalidTargets.size();
        String message = "Вариантов: " + variants.size() + ". Лучший " + best.net.variantId + ": подключено "
                + (total - best.unconnected.size()) + " из " + total
                + ", score " + String.format("%.4f", best.score)
                + (model.diagnostics.warningsTotal() > 0 ? ". Замечаний к входным данным: " + model.diagnostics.warningsTotal() : "");
        return new Result(result, message, mapper.writeValueAsString(stats));
    }

    /** Во сколько раз расширяются окна поиска при повторе. */
    static final double WIDE_WINDOW_SCALE = 2.5;

    private static boolean better(VariantSummary a, VariantSummary b) {
        if (a.unconnected.size() != b.unconnected.size()) {
            return a.unconnected.size() < b.unconnected.size();
        }
        return a.score < b.score;
    }

    ObjectNode diagnosticsJson(Diagnostics d) {
        ObjectNode n = mapper.createObjectNode();
        ObjectNode counters = n.putObject("counters");
        for (Map.Entry<String, Long> e : d.counters().entrySet()) {
            counters.put(e.getKey(), e.getValue());
        }
        n.put("warningsTotal", d.warningsTotal());
        ArrayNode w = n.putArray("warnings");
        d.warnings().forEach(w::add);
        return n;
    }

    static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }
}
