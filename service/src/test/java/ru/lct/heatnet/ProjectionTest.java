package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProjectionTest {

    /** Эталон — pyproj 3.7.2 (PROJ), EPSG:4326 → EPSG:32637, always_xy. */
    private static final double[][] REFERENCE = {
            {37.6, 55.75, 412125.459188, 6179143.323618},
            {37.6262685, 55.690915329577294, 413643.866390, 6172535.357010},
            {37.658165733407884, 55.70554663888263, 415680.374850, 6174124.284595},
            {36.1, 54.2, 310844.136874, 6009658.109738},
            {41.9, 57.3, 674717.794688, 6354503.436538},
    };

    @Test
    void matchesPyprojWithinMillimetre() {
        Projection p = new Projection();
        for (double[] r : REFERENCE) {
            Coordinate c = p.toMetric(r[0], r[1]);
            assertThat(c.x).as("x для %s,%s", r[0], r[1]).isCloseTo(r[2], within(0.001));
            assertThat(c.y).as("y для %s,%s", r[0], r[1]).isCloseTo(r[3], within(0.001));
        }
    }

    @Test
    void roundTripIsExactEnough() {
        Projection p = new Projection();
        Coordinate m = p.toMetric(37.6421, 55.7003);
        Coordinate back = p.toLonLat(m.x, m.y);
        assertThat(back.x).isCloseTo(37.6421, within(1e-9));
        assertThat(back.y).isCloseTo(55.7003, within(1e-9));
    }
}
