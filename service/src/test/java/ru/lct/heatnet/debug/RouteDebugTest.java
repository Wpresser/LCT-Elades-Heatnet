package ru.lct.heatnet.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.io.InputLoader;
import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.routing.DynamicObstacles;
import ru.lct.heatnet.routing.ObstacleField;
import ru.lct.heatnet.routing.Router;

import java.nio.file.Paths;

/** Отладка одной цели: запуск вручную (-Dtest=RouteDebugTest -Ddebug.target=2). */
class RouteDebugTest {

    @Test
    void routeSingleTarget() throws Exception {
        String id = System.getProperty("debug.target");
        if (id == null) {
            return;
        }
        InputModel m = new InputLoader(new ObjectMapper(), new AppProperties().getLimits())
                .load(Paths.get(System.getProperty("debug.input", ru.lct.heatnet.InputLoaderTest.DATASET.toString())));
        Target t = m.targets.stream().filter(x -> x.id.equals(FeatureId.of(Long.parseLong(id)))).findFirst().get();
        int dn = Integer.parseInt(System.getProperty("debug.dn", "100"));
        ObstacleField f = new ObstacleField(m, dn);
        Router r = new Router(f);
        System.out.println("вершин графа: " + f.vertexCount() + ", своих ОКС частей: " + m.ownOksOf(t).size());
        System.out.println(r.explainStart(t, m.ownOksOf(t), new DynamicObstacles(m.gf)));
        Router.Result res = r.route(t, m.ownOksOf(t), new DynamicObstacles(m.gf), DebugGoals.forModel(m, dn));
        System.out.println("результат: " + (res.route != null ? "длина " + res.route.length + " точки " + res.route.points : res.failure)
                + ", шагов " + res.expansions);
    }
}
