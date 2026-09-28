package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.routing.SpecialSpan;

import java.util.ArrayList;
import java.util.List;

final class RouteGeometry {

    /** Поворот меньше этого считается прямой (точка выхода из ОКС на луче и т. п.), град. */
    static final double STRAIGHT_DEG = 0.01;

    private RouteGeometry() {
    }

    /**
     * Убирает промежуточные вершины без поворота, объединяя спецпроходы соседних рёбер
     * (расстояния второго ребра сдвигаются на длину первого). Списки изменяются на месте.
     */
    static List<Coordinate> dropStraightVertices(List<Coordinate> pts, List<List<SpecialSpan>> spans) {
        List<Coordinate> outPts = new ArrayList<>();
        List<List<SpecialSpan>> outSpans = new ArrayList<>();
        outPts.add(pts.get(0));
        List<SpecialSpan> acc = new ArrayList<>(spans.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            Coordinate a = outPts.get(outPts.size() - 1);
            Coordinate b = pts.get(i);
            Coordinate c = pts.get(i + 1);
            if (turn(a, b, c) < STRAIGHT_DEG) {
                double shift = a.distance(b);
                for (SpecialSpan s : spans.get(i)) {
                    acc.add(new SpecialSpan(s.from + shift, s.to + shift, s.rule, s.objectId));
                }
                continue;
            }
            outPts.add(b);
            outSpans.add(acc);
            acc = new ArrayList<>(spans.get(i));
        }
        outPts.add(pts.get(pts.size() - 1));
        outSpans.add(acc);
        spans.clear();
        spans.addAll(outSpans);
        return outPts;
    }

    static double turn(Coordinate a, Coordinate b, Coordinate c) {
        double x1 = b.x - a.x;
        double y1 = b.y - a.y;
        double x2 = c.x - b.x;
        double y2 = c.y - b.y;
        double n = Math.hypot(x1, y1) * Math.hypot(x2, y2);
        if (n == 0) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (x1 * x2 + y1 * y2) / n))));
    }
}
