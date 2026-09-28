package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.routing.Router;
import ru.lct.heatnet.rules.Costs;
import ru.lct.heatnet.rules.DiameterTable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Места присоединения к уже построенной новой сети: новая камера на участке (разветвление, §2.1)
 * или уже построенная камера с запасом примыканий. В цену входит камера и удорожание общего участка
 * до присоединения из-за роста расхода.
 */
final class BranchGoals implements Router.GoalProvider {

    /** Присоединение к новой сети. */
    static final class Attach {
        /** Участок, на котором ставится новая камера (или null). */
        final Net.Line line;
        /** Уже построенная камера (или null). */
        final Net.Node chamber;
        final Coordinate point;

        Attach(Net.Line line, Net.Node chamber, Coordinate point) {
            this.line = line;
            this.chamber = chamber;
            this.point = point;
        }
    }

    static final double[] OFFSETS = {0, -5, 5, -15, 15};
    static final double MAX_RADIUS = 250;
    /** Новая камера не ближе к узлу участка, м. */
    static final double MIN_FROM_NODE = 1.0;

    private final InputModel m;
    private final Net net;
    private final double addFlow;
    private final TreeCalc.Tree tree;
    private final Map<Net.Node, List<Net.Line>> adj;
    private final STRtree index = new STRtree();
    private final Map<Net.Line, LineString> geoms = new IdentityHashMap<>();

    BranchGoals(InputModel m, Net net, double addFlow) {
        this.m = m;
        this.net = net;
        this.addFlow = addFlow;
        this.tree = TreeCalc.orient(net);
        this.adj = TreeCalc.adjacency(net);
        for (Net.Line l : net.lines) {
            LineString g = m.gf.createLineString(l.coords.toArray(new Coordinate[0]));
            geoms.put(l, g);
            index.insert(g.getEnvelopeInternal(), l);
        }
        index.build();
    }

    @Override
    public double lowerBoundDistance(Coordinate c) {
        if (net.lines.isEmpty()) {
            return Double.MAX_VALUE;
        }
        Point p = m.gf.createPoint(c);
        double best = Double.MAX_VALUE;
        double r = 50;
        while (best == Double.MAX_VALUE && r < 1e6) {
            Envelope env = new Envelope(c);
            env.expandBy(r);
            for (Object o : index.query(env)) {
                best = Math.min(best, geoms.get((Net.Line) o).distance(p));
            }
            r *= 4;
        }
        return best;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Router.Goal> near(Coordinate c) {
        List<Router.Goal> out = new ArrayList<>();
        if (net.lines.isEmpty()) {
            return out;
        }
        double lb = lowerBoundDistance(c);
        double r = Math.min(MAX_RADIUS, lb * 1.5 + 30);
        Envelope env = new Envelope(c);
        env.expandBy(r);
        Point pc = m.gf.createPoint(c);
        for (Net.Line l : (List<Net.Line>) index.query(env)) {
            LineString g = geoms.get(l);
            if (l.special || g.distance(pc) > r) {
                continue;
            }
            LengthIndexedLine lil = new LengthIndexedLine(g);
            double base = lil.project(c);
            double len = g.getLength();
            for (double off : OFFSETS) {
                double idx = base + off;
                if (idx < MIN_FROM_NODE || idx > len - MIN_FROM_NODE) {
                    continue;
                }
                Coordinate q = snapToVertex(l, lil.extractPoint(idx));
                double cost = Costs.newChamber(dnAfter(l)) + upgradeCost(l);
                out.add(new Router.Goal(q, cost, null, new Attach(l, null, q), TieInGoals.SHARED_NODE_RADIUS,
                        upstreamOf(l, lil, lil.project(q))));
            }
        }
        for (Net.Node n : net.nodes) {
            if (n.kind != Net.NodeKind.NEW_CHAMBER || n.xy.distance(c) > r) {
                continue;
            }
            List<Net.Line> at = adj.get(n);
            int existing = n.tieIn ? existingAdjacencyOnPipe(n) : 0;
            if (at == null || existing + at.size() + 1 > 4) {
                continue;
            }
            Net.Line above = tree.lineAbove.get(n);
            double cost = above == null ? 0 : upgradeCost(above);
            out.add(new Router.Goal(n.xy, cost, n.tieIn ? n.xy : null, new Attach(null, n, n.xy), TieInGoals.SHARED_NODE_RADIUS,
                    above == null ? null : neighbourOn(above, n)));
        }
        return out;
    }

    /**
     * Точка на участке в 0,5 м выше по потоку от позиции idx (к присоединению), или null, если ориентация участка
     * неизвестна. По ней проверяется поворот потока в новую ветку (≤ 90°, §2.1, разъяснение №5).
     */
    private Coordinate upstreamOf(Net.Line l, LengthIndexedLine lil, double idx) {
        Net.Node down = tree.down.get(l);
        if (down == null) {
            return null;
        }
        double step = down == l.b ? -0.5 : 0.5; // координаты участка идут от a к b
        double len = geoms.get(l).getLength();
        return lil.extractPoint(Math.max(0, Math.min(len, idx + step)));
    }

    /** Соседняя с узлом n вершина участка above (участок выше по потоку, примыкающий к n). */
    private static Coordinate neighbourOn(Net.Line above, Net.Node n) {
        List<Coordinate> c = above.coords;
        if (c.size() < 2) {
            return null;
        }
        return above.b == n ? c.get(c.size() - 2) : above.a == n ? c.get(1) : null;
    }

    /** Точка ближе 0,5 м к внутренней вершине участка ставится в саму вершину (без мелких изломов). */
    private static Coordinate snapToVertex(Net.Line l, Coordinate q) {
        for (int i = 1; i + 1 < l.coords.size(); i++) {
            if (l.coords.get(i).distance(q) < 0.5) {
                return l.coords.get(i);
            }
        }
        return q;
    }

    /** Новые камеры в точках пересечения отрезка с построенными участками. */
    @Override
    @SuppressWarnings("unchecked")
    public List<Router.Goal> onSegment(Coordinate a, Coordinate b) {
        List<Router.Goal> out = new ArrayList<>();
        LineString seg = m.gf.createLineString(new Coordinate[]{a, b});
        for (Net.Line l : (List<Net.Line>) index.query(seg.getEnvelopeInternal())) {
            LineString g = geoms.get(l);
            if (l.special) {
                continue;
            }
            LengthIndexedLine lil = new LengthIndexedLine(g);
            for (Coordinate q : g.intersection(seg).getCoordinates()) {
                double idx = lil.project(q);
                if (idx < MIN_FROM_NODE || idx > g.getLength() - MIN_FROM_NODE) {
                    continue;
                }
                double cost = Costs.newChamber(dnAfter(l)) + upgradeCost(l);
                out.add(new Router.Goal(q, cost, null, new Attach(l, null, q), TieInGoals.SHARED_NODE_RADIUS,
                        upstreamOf(l, lil, idx)));
            }
        }
        return out;
    }

    private int existingAdjacencyOnPipe(Net.Node n) {
        return new TieInGoals(m, 50, new NetworkState(m.gf, new Net("probe"))).existingAdjacencyAt(n.xy);
    }

    /** ДУ участка после добавления расхода (без учёта предельной длины). */
    private int dnAfter(Net.Line l) {
        return DiameterTable.minByFlow(l.flow + addFlow).map(r -> Math.max(r.dn, l.dn)).orElse(l.dn);
    }

    /** Удорожание участков от точки присоединения до места присоединения к существующей сети, руб. */
    private double upgradeCost(Net.Line from) {
        double extra = 0;
        for (Net.Line l = from; l != null; l = tree.parent.get(l)) {
            int dn = dnAfter(l);
            if (dn != l.dn) {
                extra += (DiameterTable.require(dn).pricePerM - DiameterTable.require(l.dn).pricePerM) * l.length() * l.kSpec;
            }
        }
        return extra;
    }
}
