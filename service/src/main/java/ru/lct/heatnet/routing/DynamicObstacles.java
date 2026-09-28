package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.List;

/**
 * Уже построенные новые линии: ось пересекать нельзя (§2.1), габариты не должны накладываться.
 * У места присоединения (своя камера, точка на ветке) проверка ослабляется: ось можно касаться в самой точке,
 * габарит не проверяется в заданном радиусе.
 */
public final class DynamicObstacles {

    private final GeometryFactory gf;
    private final List<PreparedGeometry> axes = new ArrayList<>();
    private final List<PreparedGeometry> bodies = new ArrayList<>();
    private STRtree axisIndex;
    private STRtree bodyIndex;

    public DynamicObstacles(GeometryFactory gf) {
        this.gf = gf;
    }

    /** @param bodyRadius расстояние от оси построенной линии, ближе которого не может пройти ось новой трассы */
    public void add(LineString axis, double bodyRadius) {
        axes.add(PreparedGeometryFactory.prepare(axis));
        bodies.add(PreparedGeometryFactory.prepare(axis.buffer(bodyRadius, 4)));
        axisIndex = null;
        bodyIndex = null;
    }

    public boolean isEmpty() {
        return axes.isEmpty();
    }

    public boolean free(LineString seg) {
        return free(seg, null, 0);
    }

    /** Только оси: отрезок не пересекает построенные линии (габариты не проверяются). */
    public boolean freeOfAxes(LineString seg) {
        if (axes.isEmpty()) {
            return true;
        }
        if (axisIndex == null) {
            axisIndex = index(axes);
            bodyIndex = index(bodies);
        }
        for (Object o : axisIndex.query(seg.getEnvelopeInternal())) {
            if (((PreparedGeometry) o).intersects(seg)) {
                return false;
            }
        }
        return true;
    }

    /** Проверка с ослаблением у нескольких точек (концы участка, общие узлы). */
    public boolean freeExcept(LineString seg, java.util.List<Coordinate> exempts, double exemptRadius) {
        Geometry g = seg;
        for (Coordinate c : exempts) {
            g = g.difference(gf.createPoint(c).buffer(exemptRadius, 8));
        }
        if (g.isEmpty()) {
            return true;
        }
        if (axisIndex == null) {
            axisIndex = index(axes);
            bodyIndex = index(bodies);
        }
        for (Object o : bodyIndex.query(g.getEnvelopeInternal())) {
            if (((PreparedGeometry) o).intersects(g)) {
                return false;
            }
        }
        return true;
    }

    /** Ослабление сразу у двух точек (старт перестраиваемой цепочки и место присоединения). */
    public boolean free(LineString seg, Coordinate exemptA, double radiusA, Coordinate exemptB, double radiusB) {
        if (axes.isEmpty()) {
            return true;
        }
        if (axisIndex == null) {
            axisIndex = index(axes);
            bodyIndex = index(bodies);
        }
        Geometry forAxes = seg;
        Geometry forBodies = seg;
        for (Object[] e : new Object[][]{{exemptA, radiusA}, {exemptB, radiusB}}) {
            Coordinate c = (Coordinate) e[0];
            if (c == null || (double) e[1] <= 0) {
                continue;
            }
            forAxes = forAxes.difference(gf.createPoint(c).buffer(0.01, 4));
            forBodies = forBodies.difference(gf.createPoint(c).buffer((double) e[1], 8));
        }
        return intersectsNone(axisIndex, forAxes) && intersectsNone(bodyIndex, forBodies);
    }

    private static boolean intersectsNone(STRtree index, Geometry g) {
        if (g.isEmpty()) {
            return true;
        }
        for (Object o : index.query(g.getEnvelopeInternal())) {
            if (((PreparedGeometry) o).intersects(g)) {
                return false;
            }
        }
        return true;
    }

    /** @param exempt точка присоединения (или null), у которой проверка ослаблена в радиусе exemptRadius */
    public boolean free(LineString seg, Coordinate exempt, double exemptRadius) {
        if (axes.isEmpty()) {
            return true;
        }
        if (axisIndex == null) {
            axisIndex = index(axes);
            bodyIndex = index(bodies);
        }
        Geometry forAxes = seg;
        Geometry forBodies = seg;
        if (exempt != null) {
            forAxes = seg.difference(gf.createPoint(exempt).buffer(0.01, 4));
            forBodies = seg.difference(gf.createPoint(exempt).buffer(exemptRadius, 8));
        }
        if (!forAxes.isEmpty()) {
            for (Object o : axisIndex.query(forAxes.getEnvelopeInternal())) {
                if (((PreparedGeometry) o).intersects(forAxes)) {
                    return false;
                }
            }
        }
        if (!forBodies.isEmpty()) {
            for (Object o : bodyIndex.query(forBodies.getEnvelopeInternal())) {
                if (((PreparedGeometry) o).intersects(forBodies)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static STRtree index(List<PreparedGeometry> items) {
        STRtree t = new STRtree();
        for (PreparedGeometry p : items) {
            t.insert(p.getGeometry().getEnvelopeInternal(), p);
        }
        t.build();
        return t;
    }
}
