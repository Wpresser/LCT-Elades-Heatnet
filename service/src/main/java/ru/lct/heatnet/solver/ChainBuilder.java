package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.routing.SpecialPieces;
import ru.lct.heatnet.routing.SpecialSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Превращает найденную трассу (ломаная + спецпроходы по рёбрам) в участки сети:
 * на границах спецпроходов и при смене набора ограничений ставится technical_node (§4, разъяснение №8).
 * Обычный поворот остаётся вершиной LineString.
 */
final class ChainBuilder {

    private final Net net;
    private final List<Net.Line> built = new ArrayList<>();
    private Net.Node start;
    private List<Coordinate> cur;
    private boolean special;
    private double k = 1.0;
    private List<String> labels = Collections.emptyList();

    private ChainBuilder(Net net, Net.Node from) {
        this.net = net;
        this.start = from;
        this.cur = new ArrayList<>();
        this.cur.add(from.xy);
    }

    /**
     * @param points точки от узла {@code from} к узлу {@code to}
     * @param spans  спецпроходы по рёбрам, расстояния от начала ребра в том же направлении
     */
    static List<Net.Line> build(Net net, Net.Node from, Net.Node to, List<Coordinate> points, List<List<SpecialSpan>> spans) {
        ChainBuilder b = new ChainBuilder(net, from);
        for (int i = 0; i + 1 < points.size(); i++) {
            Coordinate p = points.get(i);
            Coordinate q = points.get(i + 1);
            double len = p.distance(q);
            double s = 0;
            for (SpecialPieces.Piece piece : SpecialPieces.split(spans.get(i))) {
                if (piece.from > s + 1e-6) {
                    b.transition(at(p, q, len, s), false, 1.0, Collections.emptyList());
                }
                List<String> lb = piece.active.stream().map(SpecialSpan::label).sorted().collect(Collectors.toList());
                b.transition(at(p, q, len, piece.from), true, piece.k, lb);
                s = piece.to;
            }
            if (s < len - 1e-6) {
                b.transition(at(p, q, len, s), false, 1.0, Collections.emptyList());
            }
            if (i + 1 < points.size() - 1) {
                b.vertex(q);
            }
        }
        b.finish(to);
        return b.built;
    }

    private static Coordinate at(Coordinate p, Coordinate q, double len, double s) {
        if (s <= 0) {
            return p;
        }
        if (s >= len) {
            return q;
        }
        double t = s / len;
        return new Coordinate(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t);
    }

    /** Смена способа прокладки в точке: закрыть текущий участок technical_node, если состояние меняется. */
    private void transition(Coordinate at, boolean sp, double kk, List<String> lb) {
        if (sp == special && Objects.equals(lb, labels)) {
            return;
        }
        cut(at, null);
        special = sp;
        k = kk;
        labels = lb;
    }

    /** Вершина-поворот: спецучасток обязан быть прямым, поэтому на повороте он закрывается. */
    private void vertex(Coordinate q) {
        if (special) {
            cut(q, null);
        } else {
            add(q);
        }
    }

    private void finish(Net.Node to) {
        cut(to.xy, to);
    }

    private void add(Coordinate c) {
        if (!cur.get(cur.size() - 1).equals2D(c)) {
            cur.add(c);
        }
    }

    private void cut(Coordinate at, Net.Node end) {
        add(at);
        double length = 0;
        for (int i = 1; i < cur.size(); i++) {
            length += cur.get(i - 1).distance(cur.get(i));
        }
        if (length > 1e-6) {
            Net.Node e = end != null ? end : net.techNode(at);
            List<Coordinate> coords = new ArrayList<>(cur);
            coords.set(coords.size() - 1, e.xy);
            built.add(net.addLine(start, e, coords, special, special ? k : 1.0, labels));
            start = e;
        } else if (end != null && start != end) {
            throw new IllegalStateException("нулевой участок в конце цепочки");
        }
        cur = new ArrayList<>();
        cur.add(start.xy);
    }
}
