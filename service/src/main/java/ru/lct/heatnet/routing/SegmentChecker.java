package ru.lct.heatnet.routing;

import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Допустимость прямого отрезка трассы относительно входных данных: область поиска, запретные ограничения,
 * спецпроходы. Уже построенные новые линии проверяет {@link DynamicObstacles}.
 * Возвращает участки спецпрохода или null, если отрезок недопустим.
 */
public final class SegmentChecker {

    /** Результат для допустимого отрезка. */
    public static final class Ok {
        public final double length;
        public final List<SpecialSpan> spans;

        public Ok(double length, List<SpecialSpan> spans) {
            this.length = length;
            this.spans = spans;
        }
    }

    private final ObstacleField f;

    public SegmentChecker(ObstacleField field) {
        this.f = field;
    }

    public ObstacleField field() {
        return f;
    }

    /**
     * @param ignored   запретные ограничения, которые не учитываются (свой ОКС на финальном подходе)
     * @param tieIn     точка присоединения на существующей сети (или null): отступ до труб, проходящих
     *                  через неё, не проверяется в радиусе 2·need (подход к своей врезке)
     */
    public Ok check(Coordinate a, Coordinate b, Set<Restriction> ignored, Coordinate tieIn) {
        double len = a.distance(b);
        if (len < 1e-6) {
            return null;
        }
        LineString seg = f.segment(a, b);
        if (!f.roi.covers(seg)) {
            return null;
        }
        if (ignored.isEmpty()) {
            if (f.blockUnion.intersects(seg)) {
                return null;
            }
        } else {
            for (Object o : f.blockIndex.query(seg.getEnvelopeInternal())) {
                ObstacleField.Block blk = (ObstacleField.Block) o;
                if (!ignored.contains(blk.restriction) && blk.prepared.intersects(seg)) {
                    return null;
                }
            }
        }
        List<SpecialSpan> spans = Collections.emptyList();
        for (Object o : f.zoneIndex.query(seg.getEnvelopeInternal())) {
            ObstacleField.Zone z = (ObstacleField.Zone) o;
            if (!z.preparedZone.intersects(seg)) {
                continue;
            }
            if (tieIn != null && z.pipe != null && z.pipe.line.distance(f.gf.createPoint(tieIn)) <= InputModel.TOPO_TOL) {
                Geometry rest = seg.intersection(z.zone).difference(f.gf.createPoint(tieIn).buffer(2 * z.need, 8));
                if (!rest.isEmpty() && rest.getLength() > 1e-6) {
                    return null; // подход к своей врезке слишком пологий: идём вдоль трубы в зоне отступа
                }
                continue;
            }
            SpecialSpan span = crossing(seg, len, z, tieIn);
            if (span == null) {
                return null;
            }
            if (spans.isEmpty()) {
                spans = new ArrayList<>();
            }
            spans.add(span);
        }
        return new Ok(len, spans);
    }

    /** Спецпроход через объект зоны: весь заход отрезка в зону должен быть одним допустимым пересечением. */
    private SpecialSpan crossing(LineString seg, double len, ObstacleField.Zone z, Coordinate tieIn) {
        Coordinate a = seg.getCoordinateN(0);
        Coordinate b = seg.getCoordinateN(1);
        Geometry core = seg.intersection(z.core);
        double s0;
        double s1;
        boolean endsInside = false;
        if (z.isAreal()) {
            if (core.getNumGeometries() != 1 || !(core.getGeometryN(0) instanceof LineString)) {
                return null;
            }
            Coordinate[] cs = core.getCoordinates();
            s0 = Math.min(a.distance(cs[0]), a.distance(cs[cs.length - 1]));
            s1 = Math.max(a.distance(cs[0]), a.distance(cs[cs.length - 1]));
            // присоединение к трубе/камере под дорогой: спецпроход заканчивается в камере внутри полигона (DECISIONS №51)
            endsInside = tieIn != null && b.distance(tieIn) <= InputModel.TOPO_TOL && s1 > len - 1e-6 && s0 > 1e-6;
            if (!endsInside && (s0 < 1e-6 || s1 > len - 1e-6)) {
                return null; // конец отрезка внутри объекта
            }
            Coordinate entry = a.distance(cs[0]) <= a.distance(cs[cs.length - 1]) ? cs[0] : cs[cs.length - 1];
            Coordinate exit = entry == cs[0] ? cs[cs.length - 1] : cs[0];
            if (z.rule.minAngleDeg > 0 && (angleAt(seg, z.core, entry) < z.rule.minAngleDeg - 1e-9
                    || (!endsInside && angleAt(seg, z.core, exit) < z.rule.minAngleDeg - 1e-9))) {
                return null;
            }
        } else {
            if (core.getNumGeometries() != 1 || !(core.getGeometryN(0) instanceof Point)) {
                return null; // касание, наложение или многократное пересечение
            }
            Coordinate x = core.getCoordinate();
            s0 = s1 = a.distance(x);
            if (z.rule.minAngleDeg > 0 && angleAt(seg, z.core, x) < z.rule.minAngleDeg - 1e-9) {
                return null;
            }
        }
        double from = s0 - z.rule.specialMargin;
        double to = endsInside ? len : s1 + z.rule.specialMargin;
        // зона отступа, содержащая пересечение, целиком входит в спецучасток (режим «продлить», DECISIONS №17, №25)
        Geometry inZone = seg.intersection(z.zone);
        for (int i = 0; i < inZone.getNumGeometries(); i++) {
            Coordinate[] cs = inZone.getGeometryN(i).getCoordinates();
            if (cs.length == 0) {
                continue;
            }
            double p0 = Math.min(a.distance(cs[0]), a.distance(cs[cs.length - 1]));
            double p1 = Math.max(a.distance(cs[0]), a.distance(cs[cs.length - 1]));
            if (p0 <= s0 + 1e-6 && p1 >= s1 - 1e-6) {
                from = Math.min(from, p0);
                to = Math.max(to, p1);
            } else {
                return null; // второй заход в зону отступа без пересечения
            }
        }
        if (from < -1e-9 || to > len + 1e-9) {
            return null; // спецучасток не помещается в прямой отрезок
        }
        return new SpecialSpan(Math.max(0, from), Math.min(len, to), z.rule, z.id);
    }

    /** Угол пересечения [0°, 90°] с границей полигона или линией в точке. */
    static double angleAt(LineString seg, Geometry core, Coordinate at) {
        Geometry target = core.getDimension() == 2 ? core.getBoundary() : core;
        Coordinate[] near = DistanceOp.nearestPoints(target, seg.getFactory().createPoint(at));
        LineSegment edge = nearestEdge(target, near[0]);
        if (edge == null) {
            return 90;
        }
        double a1 = Angle.angle(seg.getCoordinateN(0), seg.getCoordinateN(1));
        double a2 = Angle.angle(edge.p0, edge.p1);
        double d = Math.toDegrees(Math.abs(Angle.normalize(a1 - a2)));
        d = d % 180.0;
        return Math.min(d, 180.0 - d);
    }

    private static LineSegment nearestEdge(Geometry lines, Coordinate p) {
        LineSegment best = null;
        double bestD = Double.MAX_VALUE;
        for (int g = 0; g < lines.getNumGeometries(); g++) {
            Coordinate[] cs = lines.getGeometryN(g).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                LineSegment s = new LineSegment(cs[i], cs[i + 1]);
                double d = s.distance(p);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
        }
        return best;
    }

    static Envelope envelope(Coordinate a, Coordinate b) {
        return new Envelope(a, b);
    }
}
