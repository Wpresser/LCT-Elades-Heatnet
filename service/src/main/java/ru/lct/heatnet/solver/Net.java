package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.model.InputObjects.ExistingChamber;
import ru.lct.heatnet.model.InputObjects.ExistingPipe;
import ru.lct.heatnet.model.InputObjects.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Новая сеть одного варианта: узлы и участки в метрах. */
public final class Net {

    public enum NodeKind {
        TARGET, EXISTING_CHAMBER, NEW_CHAMBER, TECH_NODE
    }

    public static final class Node {
        public final NodeKind kind;
        /** Для узлов из входа. */
        public final FeatureId inputId;
        /** Для новых узлов. */
        public final String outId;
        public final Coordinate xy;
        /** Исходные lon/lat для узлов из входа. */
        public final Coordinate inputLonLat;
        /** Новая камера на существующей сети: наибольший ДУ существующих труб в точке. */
        public int existingMaxDn;
        public boolean tieIn;
        public Target target;
        public ExistingChamber chamber;
        public ExistingPipe pipe;

        Node(NodeKind kind, FeatureId inputId, String outId, Coordinate xy, Coordinate inputLonLat) {
            this.kind = kind;
            this.inputId = inputId;
            this.outId = outId;
            this.xy = xy;
            this.inputLonLat = inputLonLat;
        }

        public String label() {
            return inputId != null ? inputId.toString() : outId;
        }
    }

    public static final class Line {
        public final String id;
        public final Node a;
        public final Node b;
        /** Координаты от a к b, метры. */
        public final List<Coordinate> coords;
        public final boolean special;
        public final double kSpec;
        /** Что пересекает спецучасток (тип:id), для диагностики. */
        public final List<String> crossing;
        public double flow;
        public int dn;
        /** ДУ, с габаритом которого трасса проверялась при построении. */
        public int routedDn;

        Line(String id, Node a, Node b, List<Coordinate> coords, boolean special, double kSpec, List<String> crossing) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.coords = coords;
            this.special = special;
            this.kSpec = kSpec;
            this.crossing = crossing;
        }

        public double length() {
            double l = 0;
            for (int i = 1; i < coords.size(); i++) {
                l += coords.get(i - 1).distance(coords.get(i));
            }
            return l;
        }

        public Node other(Node n) {
            return n == a ? b : a;
        }
    }

    public final String variantId;
    public final List<Node> nodes = new ArrayList<>();
    public final List<Line> lines = new ArrayList<>();
    /** Не подключённые цели → причина. */
    public final Map<Target, String> unconnected = new LinkedHashMap<>();
    /** Описание способа построения варианта. */
    public String description = "";
    /** Поиск прерван страховочным лимитом времени (не весь перебор выполнен) — пишется в diag_search_truncated. */
    public boolean searchTruncated;
    /** Цели с нестрогим финальным подходом (ближайшая точка контура недоступна). */
    public final java.util.Set<Target> relaxedApproach = new java.util.LinkedHashSet<>();
    private final Map<Object, Node> inputNodes = new LinkedHashMap<>();
    private int chamberSeq;
    private int techSeq;
    private int lineSeq;

    public Net(String variantId) {
        this.variantId = variantId;
    }

    public Node target(Target t) {
        return inputNodes.computeIfAbsent(t, k -> {
            Node n = new Node(NodeKind.TARGET, t.id, null, t.origin.getCoordinate(), t.lonLat);
            n.target = t;
            nodes.add(n);
            return n;
        });
    }

    public Node existingChamber(ExistingChamber c) {
        return inputNodes.computeIfAbsent(c, k -> {
            Node n = new Node(NodeKind.EXISTING_CHAMBER, c.id, null, c.point.getCoordinate(), c.lonLat);
            n.chamber = c;
            n.tieIn = true;
            nodes.add(n);
            return n;
        });
    }

    public Node newChamber(Coordinate xy) {
        Node n = new Node(NodeKind.NEW_CHAMBER, null, variantId + "_ch_" + (++chamberSeq), xy, null);
        nodes.add(n);
        return n;
    }

    public Node techNode(Coordinate xy) {
        Node n = new Node(NodeKind.TECH_NODE, null, variantId + "_tn_" + (++techSeq), xy, null);
        nodes.add(n);
        return n;
    }

    public Line addLine(Node a, Node b, List<Coordinate> coords, boolean special, double k, List<String> crossing) {
        Line l = new Line(variantId + "_net_" + (++lineSeq), a, b, coords, special, k, crossing);
        lines.add(l);
        return l;
    }

    /** Снимок для отката неудачного присоединения. */
    public final class Snapshot {
        final List<Node> nodes = new ArrayList<>(Net.this.nodes);
        final List<Line> lines = new ArrayList<>(Net.this.lines);
        final Map<Target, String> unconnected = new LinkedHashMap<>(Net.this.unconnected);
        final java.util.Set<Target> relaxed = new java.util.LinkedHashSet<>(relaxedApproach);
        final Map<Object, Node> input = new LinkedHashMap<>(inputNodes);
        final int[] seq = {chamberSeq, techSeq, lineSeq};
        final Map<Line, double[]> flowDn = new java.util.IdentityHashMap<>();

        Snapshot() {
            for (Line l : Net.this.lines) {
                flowDn.put(l, new double[]{l.flow, l.dn});
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot();
    }

    public void restore(Snapshot s) {
        nodes.clear();
        nodes.addAll(s.nodes);
        lines.clear();
        lines.addAll(s.lines);
        unconnected.clear();
        unconnected.putAll(s.unconnected);
        relaxedApproach.clear();
        relaxedApproach.addAll(s.relaxed);
        inputNodes.clear();
        inputNodes.putAll(s.input);
        chamberSeq = s.seq[0];
        techSeq = s.seq[1];
        lineSeq = s.seq[2];
        for (Line l : lines) {
            double[] fd = s.flowDn.get(l);
            if (fd != null) {
                l.flow = fd[0];
                l.dn = (int) fd[1];
            }
        }
    }

    /** Разрезать участок в точке q новой камерой: два участка вместо одного (разветвление, §2.1). */
    public Line[] split(Line l, Coordinate q, Node chamber) {
        int seg = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < l.coords.size(); i++) {
            double d = new org.locationtech.jts.geom.LineSegment(l.coords.get(i), l.coords.get(i + 1)).distance(q);
            if (d < best) {
                best = d;
                seg = i;
            }
        }
        List<Coordinate> c1 = new ArrayList<>(l.coords.subList(0, seg + 1));
        List<Coordinate> c2 = new ArrayList<>();
        if (!c1.get(c1.size() - 1).equals2D(q)) {
            c1.add(q);
        }
        c2.add(q);
        for (int i = seg + 1; i < l.coords.size(); i++) {
            if (!(c2.size() == 1 && l.coords.get(i).equals2D(q))) {
                c2.add(l.coords.get(i));
            }
        }
        c1.set(c1.size() - 1, chamber.xy);
        c2.set(0, chamber.xy);
        Line l1 = new Line(variantId + "_net_" + (++lineSeq), l.a, chamber, c1, l.special, l.kSpec, l.crossing);
        Line l2 = new Line(variantId + "_net_" + (++lineSeq), chamber, l.b, c2, l.special, l.kSpec, l.crossing);
        for (Line x : new Line[]{l1, l2}) {
            x.flow = l.flow;
            x.dn = l.dn;
            x.routedDn = l.routedDn;
        }
        int idx = lines.indexOf(l);
        lines.set(idx, l1);
        lines.add(idx + 1, l2);
        return new Line[]{l1, l2};
    }

    /**
     * Снять ветку цели: участки от цели вверх до первого разветвления или присоединения.
     * Камера разветвления, у которой осталось два обычных участка, убирается слиянием участков,
     * если поворот в месте слияния ≤ 90°. @return false, если снять нельзя (сеть не меняется).
     */
    public boolean removeBranch(Node target) {
        java.util.Map<Node, List<Line>> adj = new java.util.IdentityHashMap<>();
        for (Line l : lines) {
            adj.computeIfAbsent(l.a, k -> new ArrayList<>()).add(l);
            adj.computeIfAbsent(l.b, k -> new ArrayList<>()).add(l);
        }
        List<Line> at = adj.get(target);
        if (at == null || at.size() != 1) {
            return false;
        }
        List<Line> remove = new ArrayList<>();
        List<Node> removeNodes = new ArrayList<>();
        Node cur = target;
        Line l = at.get(0);
        while (true) {
            remove.add(l);
            Node up = l.other(cur);
            List<Line> upLines = adj.get(up);
            boolean passThrough = upLines.size() == 2 && !up.tieIn && up.kind != NodeKind.TARGET;
            if (!passThrough) {
                cur = up;
                break;
            }
            removeNodes.add(up);
            Line next = upLines.get(0) == l ? upLines.get(1) : upLines.get(0);
            cur = up;
            l = next;
        }
        Node stop = cur;
        List<Line> rest = new ArrayList<>(adj.get(stop));
        rest.removeAll(remove);
        Line merged = null;
        if (stop.kind == NodeKind.NEW_CHAMBER && !stop.tieIn && rest.size() == 2) {
            Line l1 = rest.get(0);
            Line l2 = rest.get(1);
            if (l1.special || l2.special) {
                return false;
            }
            List<Coordinate> c1 = new ArrayList<>(l1.b == stop ? l1.coords : reversed(l1.coords));
            List<Coordinate> c2 = new ArrayList<>(l2.a == stop ? l2.coords : reversed(l2.coords));
            Coordinate before = c1.get(c1.size() - 2);
            Coordinate after = c2.get(1);
            if (turn(before, stop.xy, after) > 90.0 + 1e-9) {
                return false;
            }
            List<Coordinate> coords = new ArrayList<>(c1);
            if (turn(before, stop.xy, after) < 0.01) {
                coords.remove(coords.size() - 1); // точка камеры лежала на прямой — лишняя вершина
            }
            coords.addAll(c2.subList(1, c2.size()));
            Node a = l1.other(stop);
            Node b = l2.other(stop);
            merged = new Line(variantId + "_net_" + (++lineSeq), a, b, coords, false, 1.0, java.util.Collections.emptyList());
            merged.routedDn = Math.min(l1.routedDn, l2.routedDn);
            merged.flow = l2.flow;
            merged.dn = l2.dn;
            remove.add(l1);
            remove.add(l2);
            removeNodes.add(stop);
        } else if (stop.kind == NodeKind.NEW_CHAMBER && stop.tieIn && rest.isEmpty()) {
            removeNodes.add(stop);
        }
        lines.removeAll(remove);
        if (merged != null) {
            lines.add(merged);
        }
        nodes.removeAll(removeNodes);
        relaxedApproach.remove(target.target);
        return true;
    }

    private static List<Coordinate> reversed(List<Coordinate> cs) {
        List<Coordinate> r = new ArrayList<>(cs);
        java.util.Collections.reverse(r);
        return r;
    }

    private static double turn(Coordinate a, Coordinate b, Coordinate c) {
        double x1 = b.x - a.x;
        double y1 = b.y - a.y;
        double x2 = c.x - b.x;
        double y2 = c.y - b.y;
        double n = Math.hypot(x1, y1) * Math.hypot(x2, y2);
        return n == 0 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (x1 * x2 + y1 * y2) / n))));
    }

    public List<Line> linesAt(Node n) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            if (l.a == n || l.b == n) {
                out.add(l);
            }
        }
        return out;
    }
}
