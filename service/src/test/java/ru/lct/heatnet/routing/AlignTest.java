package ru.lct.heatnet.routing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignTest {

    @AfterEach
    void clear() {
        System.clearProperty("heatnet.align");
    }

    @Test
    void alignmentIsOnByDefaultAndCanBeSwitchedOff() {
        System.clearProperty("heatnet.align");
        if (System.getenv("HEATNET_ALIGN") == null) {
            assertTrue(Router.alignEnabled());
        }
        System.setProperty("heatnet.align", "false");
        assertFalse(Router.alignEnabled());
        System.setProperty("heatnet.align", "true");
        assertTrue(Router.alignEnabled());
    }

    @Test
    void zigzagCountsOnlyShortOppositeTurns() {
        // Г: один поворот — не зигзаг
        assertEquals(0, Router.zigzags(Arrays.asList(c(0, 0), c(50, 0), c(50, 50))));
        // Z с перескоком 40 м — длинный, не зигзаг
        assertEquals(0, Router.zigzags(Arrays.asList(c(0, 0), c(50, 0), c(50, 40), c(100, 40))));
        // пила: повороты в разные стороны через 10 м
        assertEquals(1, Router.zigzags(Arrays.asList(c(0, 0), c(50, 0), c(50, 10), c(100, 10))));
        // повороты в одну сторону (обход угла) — не зигзаг
        assertEquals(0, Router.zigzags(Arrays.asList(c(0, 0), c(50, 0), c(55, 10), c(55, 60))));
    }

    private static final GeometryFactory GF = new GeometryFactory();

    /** Поворот сцены, чтобы оси не совпадали с осями координат. */
    private static Coordinate rot(double x, double y, double deg) {
        double a = Math.toRadians(deg);
        return new Coordinate(x * Math.cos(a) - y * Math.sin(a), x * Math.sin(a) + y * Math.cos(a));
    }

    private static Polygon rect(double x0, double y0, double x1, double y1, double deg) {
        return GF.createPolygon(new Coordinate[]{rot(x0, y0, deg), rot(x1, y0, deg), rot(x1, y1, deg), rot(x0, y1, deg),
                rot(x0, y0, deg)});
    }

    private static Router router(int dn, Geometry... oks) {
        InputModel m = new InputModel(GF);
        m.roi = GF.toGeometry(new Envelope(-300, 300, -300, 300));
        for (Geometry g : oks) {
            m.restrictions.add(new Restriction(null, "oks", RestrictionRule.OKS, g));
        }
        return new Router(new ObstacleField(m, dn));
    }

    @Test
    void alignmentNeverMakesRouteCrossItself() {
        // Регрессия ревью 18.10: «Г» вместо первой диагонали пересекала дальний конец той же трассы
        Router router = router(100, rect(45, -12, 55, -8, 0.5), rect(0, 60, 100, 65, 0.5));
        double[][] xy = {{0, 0}, {50, 50}, {100, 50}, {100, -20}, {20, -20}, {20, 10}};
        List<Coordinate> pts = new ArrayList<>();
        for (double[] p : xy) {
            pts.add(rot(p[0], p[1], 0.5));
        }
        List<List<SpecialSpan>> spans = new ArrayList<>();
        double length = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            SegmentChecker.Ok ok = router.checkSegment(pts.get(i), pts.get(i + 1), Collections.emptySet(), null);
            assertNotNull(ok, "входной отрезок " + i + " допустим");
            spans.add(ok.spans);
            length += ok.length;
        }
        LineString input = GF.createLineString(pts.toArray(new Coordinate[0]));
        assertTrue(input.isSimple());
        Router.Goal goal = new Router.Goal(pts.get(pts.size() - 1), 0, null, null, 0);
        Router.Route out = router.align(new Router.Route(pts, spans, goal, length, 0), new DynamicObstacles(GF), false, 0);
        LineString result = GF.createLineString(out.points.toArray(new Coordinate[0]));
        assertTrue(result.isSimple(), "после выравнивания трасса пересекает сама себя: " + result);
        assertTrue(router.selfClear(out.points, 0, out.points.size() - 2));
    }

    private static Router.Route route(Router router, Coordinate... pts) {
        List<Coordinate> list = Arrays.asList(pts);
        List<List<SpecialSpan>> spans = new ArrayList<>();
        double length = 0;
        for (int i = 0; i + 1 < pts.length; i++) {
            SegmentChecker.Ok ok = router.checkSegment(pts[i], pts[i + 1], Collections.emptySet(), null);
            assertNotNull(ok, "входной отрезок " + i + " допустим");
            spans.add(ok.spans);
            length += ok.length;
        }
        return new Router.Route(new ArrayList<>(list), spans, new Router.Goal(pts[pts.length - 1], 0, null, null, 0), length, 0);
    }

    @Test
    void shortBendOfOneTurnDirectionIsMergedIntoOneVertex() {
        // скругление обхода: повороты 35,5° и 46,2° в одну сторону через 0,86 м → один поворот 81,7°
        Router router = router(100);
        Router.Route out = router.mergeShortBends(route(router, c(0, 0), c(50, 0), c(50.7, 0.5), c(55, 30)),
                new DynamicObstacles(GF), false, 0);
        assertEquals(3, out.points.size());
        assertEquals(0, out.points.get(1).y, 1e-9);
        assertTrue(out.points.get(1).x > 50 && out.points.get(1).x < 50.7);
        assertEquals(out.points.get(2), c(55, 30));
        assertTrue(Router.turn(out.points.get(0), out.points.get(1), out.points.get(2)) <= Router.MAX_TURN_DEG);
    }

    @Test
    void zigzagAndLongBendAreNotMerged() {
        Router router = router(100);
        // повороты в разные стороны — ступенька, не скругление
        Router.Route zig = route(router, c(0, 0), c(50, 0), c(50.5, 0.6), c(100, 0.6));
        assertEquals(4, router.mergeShortBends(zig, new DynamicObstacles(GF), false, 0).points.size());
        // отрезок между поворотами 1,5 м — обычный обход, не мелкий излом
        Router.Route bend = route(router, c(0, 0), c(50, 0), c(51.2, 0.9), c(55, 30));
        assertEquals(4, router.mergeShortBends(bend, new DynamicObstacles(GF), false, 0).points.size());
    }

    @Test
    void axisDoesNotJoinSeparatePartsOfMultiPolygon() {
        // два квадрата 1×1 м: настоящих стен 8 м < 20 м — оси нет (раньше склейка частей давала 45,5°)
        Geometry two = GF.createMultiPolygon(new Polygon[]{rect(-25.5, -25.5, -24.5, -24.5, 0.5), rect(24.5, 24.5, 25.5, 25.5, 0.5)});
        assertTrue(Double.isNaN(router(50, two).localAxisDeg(new Coordinate(0, 0))));
    }

    @Test
    void axisOfBuildingWithCourtyard() {
        Coordinate[] shell = rect(-20, -20, 20, 20, 0.5).getExteriorRing().getCoordinates();
        Coordinate[] hole = rect(-10, -10, 10, 10, 0.5).getExteriorRing().getCoordinates();
        Polygon yard = GF.createPolygon(GF.createLinearRing(shell), new LinearRing[]{GF.createLinearRing(hole)});
        assertEquals(0.5, router(50, yard).localAxisDeg(new Coordinate(0, 0)), 1.0);
    }

    @Test
    void axisFollowsRotationAndIgnoresPartOrder() {
        Polygon a = rect(-40, -5, -10, 5, 30);
        Polygon b = rect(10, -5, 40, 5, 30);
        double ab = router(50, GF.createMultiPolygon(new Polygon[]{a, b})).localAxisDeg(new Coordinate(0, 0));
        double ba = router(50, GF.createMultiPolygon(new Polygon[]{b, a})).localAxisDeg(new Coordinate(0, 0));
        assertEquals(ab, ba, 1e-9);
        assertEquals(30.0, ab, 1.0);
    }

    private static Coordinate c(double x, double y) {
        return new Coordinate(x, y);
    }
}
