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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прогон синтетических сценариев из ../scenarios: результаты пишутся в target/scenarios/<имя>/result.geojson,
 * проверка правил — scenarios/check_scenarios.py (валидатор + ожидания).
 */
class ScenarioTest {

    @Test
    void solvesAllScenarios() throws Exception {
        Path root = Paths.get("../scenarios");
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> inputs;
        try (Stream<Path> s = Files.list(root)) {
            inputs = s.map(d -> d.resolve("input.geojson")).filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        ObjectMapper mapper = new ObjectMapper();
        for (Path in : inputs) {
            String name = in.getParent().getFileName().toString();
            InputModel m = new InputLoader(mapper, new AppProperties().getLimits()).load(in);
            List<VariantSummary> variants = new SolveService().solve(m);
            Path out = Paths.get("target/scenarios", name, "result.geojson");
            Files.createDirectories(out.getParent());
            new ResultWriter(mapper).write(out, variants);
            VariantSummary best = variants.get(0);
            System.out.printf("сценарий %s: лучший %s, подключено %d/%d, S=%.4f, причины: %s%n", name, best.net.variantId,
                    m.targets.size() - best.unconnected.size(), m.targets.size(), best.score, best.net.unconnected.values());
            assertThat(variants).isNotEmpty();
        }
    }
}
