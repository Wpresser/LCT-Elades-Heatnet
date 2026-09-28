package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.ExistingChamber;
import ru.lct.heatnet.model.InputObjects.ExistingPipe;
import ru.lct.heatnet.routing.Router;
import ru.lct.heatnet.routing.TieIn;
import ru.lct.heatnet.rules.Costs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Места присоединения к существующей сети (§2.4, разъяснения №11–12):
 * любая точка трубы; если точка ≤ 10 м от существующей камеры с запасом примыканий — только эта камера.
 */
final class TieInGoals implements Router.GoalProvider {

    static final double TEN_METRES = 10.0;
    /** Смещения вдоль трубы от ближайшей точки, м: косой подход, когда перпендикуляр закрыт. */
    static final double[] OFFSETS = {0, -5, 5, -15, 15, -40, 40};
    static final double MAX_RADIUS = 250;
    static final int MAX_PIPES = 6;

    private final InputModel m;
    private final int dn;
    private final NetworkState state;

    TieInGoals(InputModel m, int dn, NetworkState state) {
        this.m = m;
        this.dn = dn;
        this.state = state;
    }

    @Override
    public double lowerBoundDistance(Coordinate c) {
        Point p = m.gf.createPoint(c);
        Object nearest = m.pipeIndex.nearestNeighbour(new Envelope(c), p, PIPE_DISTANCE);
        return nearest == null ? 0 : ((ExistingPipe) nearest).line.distance(p);
    }

    private static final ItemDistance PIPE_DISTANCE = (ItemBoundable a, ItemBoundable b) -> {
        Object x = a.getItem();
        Object y = b.getItem();
        ExistingPipe pipe = (ExistingPipe) (x instanceof ExistingPipe ? x : y);
        Point pt = (Point) (x instanceof Point ? x : y);
        return pipe.line.distance(pt);
    };

    @Override
    public List<Router.Goal> near(Coordinate c) {
        double lb = lowerBoundDistance(c);
        double r = Math.min(MAX_RADIUS, lb * 1.5 + 30);
        Envelope env = new Envelope(c);
        env.expandBy(r);
        Point pc = m.gf.createPoint(c);
        List<ExistingPipe> pipes = new ArrayList<>(m.pipesNear(env));
        pipes.sort(Comparator.comparingDouble(p -> p.line.distance(pc)));
        List<Router.Goal> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExistingPipe pipe : pipes.subList(0, Math.min(MAX_PIPES, pipes.size()))) {
            if (pipe.line.distance(pc) > r) {
                continue;
            }
            LengthIndexedLine lil = new LengthIndexedLine(pipe.line);
            double base = lil.project(c);
            for (double off : OFFSETS) {
                double idx = Math.max(lil.getStartIndex(), Math.min(lil.getEndIndex(), base + off));
                addPointOnPipe(lil.extractPoint(idx), pipe, lil, out, seen);
            }
        }
        for (ExistingChamber ch : m.chambersNear(env)) {
            addChamber(ch, out, seen);
        }
        return out;
    }

    /** Новые камеры в точках пересечения отрезка с трубами (правило 10 м соблюдается). */
    @Override
    public List<Router.Goal> onSegment(Coordinate a, Coordinate b) {
        List<Router.Goal> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        org.locationtech.jts.geom.LineString seg = m.gf.createLineString(new Coordinate[]{a, b});
        for (ExistingPipe pipe : m.pipesNear(seg.getEnvelopeInternal())) {
            org.locationtech.jts.geom.Geometry x = pipe.line.intersection(seg);
            for (Coordinate q : x.getCoordinates()) {
                if (roomyChamberWithin(q, TEN_METRES) == null) {
                    addNew(q, pipe, out, seen);
                }
            }
        }
        return out;
    }

    private void addPointOnPipe(Coordinate q, ExistingPipe pipe, LengthIndexedLine lil, List<Router.Goal> out, Set<String> seen) {
        ExistingChamber close = roomyChamberWithin(q, TEN_METRES);
        if (close != null) {
            // §2.4: рядом камера с запасом примыканий — присоединяемся к ней; плюс точки трубы за пределами 10 м
            addChamber(close, out, seen);
            double ci = lil.project(close.point.getCoordinate());
            for (double off : new double[]{-TEN_METRES - 0.5, TEN_METRES + 0.5}) {
                double idx = ci + off;
                if (idx < lil.getStartIndex() || idx > lil.getEndIndex()) {
                    continue;
                }
                Coordinate q2 = lil.extractPoint(idx);
                if (roomyChamberWithin(q2, TEN_METRES) == null) {
                    addNew(q2, pipe, out, seen);
                }
            }
            return;
        }
        addNew(q, pipe, out, seen);
    }

    private void addNew(Coordinate q, ExistingPipe pipe, List<Router.Goal> out, Set<String> seen) {
        for (Coordinate used : state.newChamberPoints) {
            if (used.distance(q) < 1.0) {
                return;
            }
        }
        int adjacency = existingAdjacencyAt(q);
        if (adjacency + 1 > 4) {
            return;
        }
        if (!seen.add("n" + Math.round(q.x * 10) + ":" + Math.round(q.y * 10))) {
            return;
        }
        int maxDn = existingMaxDnAt(q);
        TieIn t = new TieIn(TieIn.Kind.NEW_ON_PIPE, q, null, pipe, Costs.newChamber(Math.max(dn, maxDn)), maxDn);
        out.add(new Router.Goal(q, t.cost, q, t, SHARED_NODE_RADIUS));
    }

    /** Ослабление проверки наложения на построенные линии у общей камеры, м. */
    static final double SHARED_NODE_RADIUS = 3.0;

    private void addChamber(ExistingChamber ch, List<Router.Goal> out, Set<String> seen) {
        if (!state.hasRoom(ch) || !seen.add("c" + ch.id)) {
            return;
        }
        Coordinate q = ch.point.getCoordinate();
        TieIn t = new TieIn(TieIn.Kind.EXISTING_CHAMBER, q, ch, null, Costs.EXISTING_CHAMBER_TIE_IN, existingMaxDnAt(q));
        out.add(new Router.Goal(q, t.cost, q, t, SHARED_NODE_RADIUS));
    }

    private ExistingChamber roomyChamberWithin(Coordinate q, double r) {
        Envelope env = new Envelope(q);
        env.expandBy(r);
        ExistingChamber best = null;
        double bestD = Double.MAX_VALUE;
        for (ExistingChamber ch : m.chambersNear(env)) {
            double d = ch.point.getCoordinate().distance(q);
            if (d <= r && state.hasRoom(ch) && d < bestD) {
                best = ch;
                bestD = d;
            }
        }
        return best;
    }

    /** Примыкания существующих труб в точке: конец линии = 1, проходящая линия = 2. */
    int existingAdjacencyAt(Coordinate q) {
        Envelope env = new Envelope(q);
        env.expandBy(InputModel.TOPO_TOL);
        Point pq = m.gf.createPoint(q);
        int adj = 0;
        for (ExistingPipe p : m.pipesNear(env)) {
            Coordinate a = p.line.getCoordinateN(0);
            Coordinate b = p.line.getCoordinateN(p.line.getNumPoints() - 1);
            int ends = (a.distance(q) <= InputModel.TOPO_TOL ? 1 : 0) + (b.distance(q) <= InputModel.TOPO_TOL ? 1 : 0);
            if (ends > 0) {
                adj += ends;
            } else if (p.line.distance(pq) <= InputModel.TOPO_TOL) {
                adj += 2;
            }
        }
        return adj;
    }

    int existingMaxDnAt(Coordinate q) {
        Envelope env = new Envelope(q);
        env.expandBy(InputModel.TOPO_TOL);
        Point pq = m.gf.createPoint(q);
        int max = 0;
        for (ExistingPipe p : m.pipesNear(env)) {
            if (p.line.distance(pq) <= InputModel.TOPO_TOL) {
                max = Math.max(max, p.diameter);
            }
        }
        return max;
    }
}
