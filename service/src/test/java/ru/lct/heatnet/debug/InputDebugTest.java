package ru.lct.heatnet.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.io.InputLoader;
import ru.lct.heatnet.io.ResultWriter;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.solver.SolveService;
import ru.lct.heatnet.solver.VariantSummary;

import java.nio.file.Paths;
import java.util.List;

/** Прогон произвольного входа вручную: -Dtest=InputDebugTest -Ddebug.input=путь -Ddebug.output=путь. */
class InputDebugTest {

    @Test
    void solveInput() throws Exception {
        String in = System.getProperty("debug.input");
        if (in == null) {
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        InputModel m = new InputLoader(mapper, new AppProperties().getLimits()).load(Paths.get(in));
        List<VariantSummary> variants = new SolveService().solve(m);
        new ResultWriter(mapper).write(Paths.get(System.getProperty("debug.output", "target/debug-result.geojson")), variants);
        for (VariantSummary v : variants) {
            System.out.printf("%s: подключено %d/%d, S=%.4f%n", v.net.variantId, m.targets.size() - v.unconnected.size(),
                    m.targets.size(), v.score);
        }
    }
}
