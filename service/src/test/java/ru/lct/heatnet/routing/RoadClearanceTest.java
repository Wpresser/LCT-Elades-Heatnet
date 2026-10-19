package ru.lct.heatnet.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.io.InputLoader;
import ru.lct.heatnet.model.InputModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** Отрезок у дороги: прохождение рядом в зоне отступа недопустимо, пересечение — спецпроход (регрессия: падение). */
class RoadClearanceTest {

    @Test
    void passingBesideRoadIsRejectedAndCrossingIsSpecial(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("road.geojson");
        String road = "{\"type\":\"Polygon\",\"coordinates\":[[[37.6010,55.7000],[37.6030,55.7000],[37.6030,55.7001],"
                + "[37.6010,55.7001],[37.6010,55.7000]]]}";
        Files.write(f, ("{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"oks_connection_point\",\"flow_tph\":10},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6020,55.7010]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":2,\"object_type\":\"heat_network\",\"diameter\":300},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6000,55.6990],[37.6040,55.6990]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"r\",\"object_type\":\"restriction\",\"restriction_type\":\"road\"},"
                + "\"geometry\":" + road + "}]}").getBytes(StandardCharsets.UTF_8));
        InputModel m = new InputLoader(new ObjectMapper(), new AppProperties().getLimits()).load(f);
        SegmentChecker checker = new SegmentChecker(new ObstacleField(m, 100));
        Envelope e = m.restrictions.get(0).geometry.getEnvelopeInternal();
        // параллельно верхней границе дороги в 1 м (отступ 1,5 м + полгабарита): в зоне отступа, но без пересечения
        Coordinate a = new Coordinate(e.getMinX() + 5, e.getMaxY() + 1.0);
        Coordinate b = new Coordinate(e.getMaxX() - 5, e.getMaxY() + 1.0);
        assertThat(checker.check(a, b, Collections.emptySet(), null)).isNull();
        // поперёк дороги под прямым углом — допустимо, весь проход — спецучасток
        double cx = (e.getMinX() + e.getMaxX()) / 2;
        SegmentChecker.Ok cross = checker.check(new Coordinate(cx, e.getMinY() - 15), new Coordinate(cx, e.getMaxY() + 15),
                Collections.emptySet(), null);
        assertThat(cross).isNotNull();
        assertThat(cross.spans).hasSize(1);
    }
}
