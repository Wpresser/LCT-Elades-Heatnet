package ru.lct.heatnet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.io.InputLoader;
import ru.lct.heatnet.io.ResultWriter;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.solver.SolveService;
import ru.lct.heatnet.solver.VariantSummary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прогон на конкурсном наборе; результат пишется в target/dataset-result.geojson
 * для проверки внешним валидатором (validator/validator.py).
 */
class SolverDatasetTest {

    @Test
    void solvesCompetitionDataset() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputModel m = new InputLoader(mapper, new AppProperties().getLimits()).load(InputLoaderTest.DATASET);
        List<VariantSummary> variants = new SolveService().solve(m);
        Path out = Paths.get("target/dataset-result.geojson");
        new ResultWriter(mapper).write(out, variants);

        assertThat(variants).isNotEmpty();
        assertThat(Files.size(out)).isPositive();
        for (VariantSummary v : variants) {
            System.out.printf("%s rank=%d: подключено %d/%d, L=%.1f м, C=%.0f руб, S=%.4f, не подключены: %s%n",
                    v.net.variantId, v.rank, m.targets.size() - v.unconnected.size(), m.targets.size(),
                    v.length, v.calculated, v.score, v.net.unconnected.values());
        }
        // §2.5: неподключение допустимо, только если маршрута нет — ни один вариант не бросает цель,
        // которую подключает вариант с наибольшим покрытием
        VariantSummary widest = variants.stream().min(java.util.Comparator.comparingInt(v -> v.unconnected.size())).get();
        for (VariantSummary v : variants) {
            assertThat(widest.unconnected).as("%s не подключает цели, подключённые в %s", v.net.variantId, widest.net.variantId)
                    .containsAll(v.unconnected);
        }
    }
}
