package ru.lct.heatnet.routing;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.rules.DiameterTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Поиск трассы от цели до места присоединения: A* по графу видимости, где состояние — пара
 * (предыдущая вершина, текущая), чтобы точно проверять поворот ≤ 90° (§2.1).
 * Цена ребра — его вклад в итоговый показатель S (0,7·C/25 млн + 0,3·L/100), поэтому спецпроходы
 * и стоимость камер учитываются при выборе трассы.
 */
public final class Router {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Router.class);

    public static final double MAX_TURN_DEG = 90.0;
    /** Радиус поиска соседей в графе видимости, м. */
    static final double NEIGHBOUR_RADIUS = 600.0;
    static final int MAX_EXPANSIONS = 60_000;
    /** На сколько далеко от границы своего ОКС можно искать начало финального участка, м. */
    static final double MAX_APPROACH = 80.0;
    static final double APPROACH_STEP = 0.25;

    /** Место, где трасса может закончиться (присоединение к существующей сети или к новой ветке). */
    public static final class Goal {
        public final Coordinate point;
        /** Стоимость камеры/врезки, руб. */
        public final double cost;
        /** Точка, у которой не проверяется отступ до существующих труб (своя врезка), или null. */
        public final Coordinate exemptPoint;
        public final Object payload;
        /** Радиус у точки окончания, где не проверяется наложение на уже построенные новые линии, м. */
        public final double dynExemptRadius;
        /**
         * Точка выше по потоку на родительском участке новой сети (разветвление в камере) или null. Поток, входящий
         * в камеру с этой стороны, не должен менять направление при переходе в новую ветку больше чем на 90°
         * (§2.1, разъяснение №5).
         */
        public final Coordinate inflowFrom;

        public Goal(Coordinate point, double cost, Coordinate exemptPoint, Object payload, double dynExemptRadius) {
            this(point, cost, exemptPoint, payload, dynExemptRadius, null);
        }

        public Goal(Coordinate point, double cost, Coordinate exemptPoint, Object payload, double dynExemptRadius,
                    Coordinate inflowFrom) {
            this.point = point;
            this.cost = cost;
            this.exemptPoint = exemptPoint;
            this.payload = payload;
            this.dynExemptRadius = dynExemptRadius;
            this.inflowFrom = inflowFrom;
        }

        /** Поворот потока в точке окончания при выходе в ветку к {@code next} не больше 90°. */
        boolean branchTurnOk(Coordinate next) {
            return inflowFrom == null || turnOk(inflowFrom, point, next);
        }
    }

    public interface GoalProvider {
        /** Кандидаты окончания трассы рядом с точкой. */
        List<Goal> near(Coordinate c);

        /** Нижняя оценка расстояния от точки до ближайшей цели, м. */
        double lowerBoundDistance(Coordinate c);

        /** Места присоединения прямо на отрезке a→b (пересечения с трубами и ветками). */
        default List<Goal> onSegment(Coordinate a, Coordinate b) {
            return Collections.emptyList();
        }
    }

    public static final class Route {
        /** Точки от цели к месту присоединения. */
        public final List<Coordinate> points;
        /** Спецпроходы по рёбрам (расстояния от начала ребра). */
        public final List<List<SpecialSpan>> spans;
        public final Goal goal;
        public final double length;
        public final double scoreCost;
        /** Финальный подход не через ближайшую точку контура (ближайшая недоступна), DECISIONS №27. */
        public boolean relaxedApproach;

        Route(List<Coordinate> points, List<List<SpecialSpan>> spans, Goal goal, double length, double scoreCost) {
            this.points = points;
            this.spans = spans;
            this.goal = goal;
            this.length = length;
            this.scoreCost = scoreCost;
        }
    }

    /** Итог поиска: трасса или причина неудачи. */
    public static final class Result {
        public final Route route;
        public final String failure;
        public final int expansions;

        Result(Route route, String failure, int expansions) {
            this.route = route;
            this.failure = failure;
            this.expansions = expansions;
        }
    }

    private static final class Edge {
        final int to;
        final SegmentChecker.Ok ok;

        Edge(int to, SegmentChecker.Ok ok) {
            this.to = to;
            this.ok = ok;
        }
    }

    private final ObstacleField field;
    private final SegmentChecker checker;
    private final double pricePerM;
    /** Кэш видимости между статическими вершинами (не зависит от построенных линий). */
    private final Map<Integer, List<Edge>> visibility = new HashMap<>();

    public Router(ObstacleField field) {
        this.field = field;
        this.checker = new SegmentChecker(field);
        this.pricePerM = DiameterTable.require(field.dn).pricePerM;
    }

    public ObstacleField field() {
        return field;
    }

    /** Вклад метра трубы с коэффициентом K в показатель S. */
    public double weight(double k) {
        return 0.7 * pricePerM * k / 25_000_000.0 + 0.3 / 100.0;
    }

    /** Проверка отрезка по входным ограничениям с габаритом этого ДУ (для перепроверки после роста ДУ). */
    public SegmentChecker.Ok checkSegment(Coordinate a, Coordinate b, Set<Restriction> ignored, Coordinate exempt) {
        return checker.check(a, b, ignored, exempt);
    }

    public double costOf(SegmentChecker.Ok ok) {
        return ok.length * weight(1) + specialExtra(ok);
    }

    /** Добавка за спецпроходы: на перекрытиях берётся наибольший K. */
    private double specialExtra(SegmentChecker.Ok ok) {
        if (ok.spans.isEmpty()) {
            return 0;
        }
        List<double[]> pieces = SpecialPieces.pieces(ok.spans);
        double extra = 0;
        for (double[] p : pieces) {
            extra += (p[1] - p[0]) * (weight(p[2]) - weight(1));
        }
        return extra;
    }

    public Result route(Target target, List<Restriction> ownOks, DynamicObstacles dyn, GoalProvider goals) {
        return route(target, ownOks, dyn, goals, true, null);
    }

    public Result route(Target target, List<Restriction> ownOks, DynamicObstacles dyn, GoalProvider goals, boolean allowRelaxed) {
        return route(target, ownOks, dyn, goals, allowRelaxed, null);
    }

    /**
     * @param allowRelaxed разрешён ли нестрогий финальный подход. Даже если разрешён, он применяется только когда
     *                     строгий невозможен по входным данным (без учёта уже построенных новых линий), DECISIONS №27.
     */
    /**
     * @param strictProbe маршрутизатор с наименьшим возможным ДУ цели: им проверяется, возможен ли строгий подход
     *                    по входным данным (с широким габаритом он может быть закрыт, а с фактическим ДУ — нет)
     */
    public Result route(Target target, List<Restriction> ownOks, DynamicObstacles dyn, GoalProvider goals, boolean allowRelaxed,
                        Router strictProbe) {
        allowRelaxed = allowRelaxed && TerminalPolicy.current() == TerminalPolicy.RELAXED;
        Coordinate t = target.point.getCoordinate();
        if (ownOks.isEmpty()) {
            return search(target, ownOks, Collections.emptyList(), false, dyn, goals, 0);
        }
        Result strictResult = null;
        List<Coordinate> strict = portals(t, ownOks, dyn, true);
        if (!strict.isEmpty()) {
            strictResult = search(target, ownOks, strict, false, dyn, goals, 0);
            if (strictResult.route != null) {
                strictResult.route.relaxedApproach |= nonLiteral(t, ownOks);
                return strictResult;
            }
        }
        if (log.isDebugEnabled() || Boolean.getBoolean("heatnet.debug")) {
            log.info("цель {}: строгий выход {}, поиск из него: {}", target.id,
                    strict.isEmpty() ? "не найден" : strict.get(0),
                    strictResult == null ? "-" : strictResult.failure + " (шагов " + strictResult.expansions + ")");
        }
        // строгий луч пересекает трубу или построенную ветку — врезаемся прямо на луче
        Result direct = directOnRay(target, ownOks, dyn, goals);
        if (direct != null) {
            if (direct.route != null) {
                direct.route.relaxedApproach |= nonLiteral(t, ownOks);
            }
            return direct;
        }
        // нестрогий подход — только если строгий невозможен и без наших построенных линий
        DynamicObstacles none = new DynamicObstacles(field.gf);
        Router probe = strictProbe != null ? strictProbe : this;
        if (!allowRelaxed) {
            if (strictResult != null) {
                return strictResult;
            }
            // выход закрыт уже по входным данным при наименьшем ДУ, а весь луч лежит в окнах поиска: расширение
            // окон добавляет только ограничения и его не откроет (HeatNetJobProcessor не повторяет расчёт ради таких
            // целей). Действует в literal/exterior; в relaxed причина перезаписывается нестрогой попыткой
            boolean closed = probe.portals(t, ownOks, none, true).isEmpty() && probe.strictRayInsideRoi(t, ownOks);
            return new Result(null, closed ? STRICT_EXIT_CLOSED : "строгий финальный подход недоступен (§2.2)", 0);
        }
        List<Coordinate> staticStrict = probe.portals(t, ownOks, none, true);
        if (!staticStrict.isEmpty() && probe.search(target, ownOks, staticStrict, false, none, goals, 0).route != null) {
            return new Result(null, "строгий финальный подход перекрыт уже построенными линиями (§2.2)", 0);
        }
        // строгий выход закрыт или из него нет трассы — пробуем несколько нестрогих выходов сразу: сначала те, у которых
        // участок внутри своего ОКС не больше чем на RELAXED_PREFERRED_EXTRA длиннее ближайшего, — финальный участок
        // не ведётся через значительную часть ОКС ради сокращения внешней трассы (доп. разъяснения 29.09, №15)
        // Среди них сначала «чистые» выходы: снаружи луч только удаляется от своего здания и не идёт вдоль его
        // других крыльев внутри зоны отступа
        for (boolean clean : new boolean[]{true, false}) {
            List<Coordinate> near = relaxedPortals(t, ownOks, dyn, RELAXED_PREFERRED_EXTRA, clean);
            if (!near.isEmpty()) {
                Result rn = search(target, ownOks, near, true, dyn, goals, 0);
                if (rn.route != null) {
                    return rn;
                }
            }
        }
        List<Coordinate> relaxed = portals(t, ownOks, dyn, false);
        if (relaxed.isEmpty()) {
            return strictResult != null ? strictResult : new Result(null, "финальный подход к цели заблокирован: ни от одной "
                    + "точки контура своего ОКС нет свободного прямого выхода (§2.2)", 0);
        }
        Result r = search(target, ownOks, relaxed, true, dyn, goals, 0);
        return r.route != null || strictResult == null ? r : strictResult;
    }

    /** @param startExemptRadius радиус ослабления проверки построенных линий у старта (перестройка от камеры), 0 — нет */
    private Result search(Target target, List<Restriction> ownOks, List<Coordinate> portals, boolean relaxed,
                          DynamicObstacles dyn, GoalProvider goals, double startExemptRadius) {
        return search(target, ownOks, portals, relaxed, dyn, goals, startExemptRadius, Collections.emptyList());
    }

    /**
     * @param startOut у старта-камеры — соседние вершины уже примыкающих участков ниже по потоку: поток приходит
     *                 в старт по первому отрезку трассы и в каждый из них поворачивает не больше чем на 90°
     *                 (§2.1, разъяснение №5; перестройка цепочки от камеры разветвления)
     */
    private Result search(Target target, List<Restriction> ownOks, List<Coordinate> portals, boolean relaxed,
                          DynamicObstacles dyn, GoalProvider goals, double startExemptRadius, List<Coordinate> startOut) {
        List<Coordinate> nodes = new ArrayList<>(field.vertices);
        int v = nodes.size();
        Coordinate t = target.point.getCoordinate();
        nodes.add(t);
        Map<Integer, Goal> goalNodes = new HashMap<>();
        Map<Long, State> states = new HashMap<>();
        PriorityQueue<State> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        if (portals.isEmpty()) {
            State s0 = new State(-1, v, 0, null, null);
            s0.f = heuristic(t, goals);
            states.put(key(-1, v), s0);
            open.add(s0);
        } else {
            Set<Restriction> ignored = new HashSet<>(ownOks);
            for (Coordinate a : portals) {
                SegmentChecker.Ok e = checker.check(t, a, ignored, null);
                if (e == null) {
                    continue;
                }
                int idx = nodes.size();
                nodes.add(a);
                State s0 = new State(v, idx, costOf(e), null, e);
                s0.f = s0.g + heuristic(a, goals);
                states.put(key(v, idx), s0);
                open.add(s0);
            }
        }
        int staticCount = v;
        int expansions = 0;
        while (!open.isEmpty()) {
            State s = open.poll();
            if (s.closed || states.get(key(s.prev, s.cur)) != s) {
                continue; // уже обработано или найден более дешёвый путь в это же состояние
            }
            s.closed = true;
            if (goalNodes.containsKey(s.cur)) {
                Route raw = reconstruct(s, nodes, goalNodes.get(s.cur));
                if (!simple(raw.points)) {
                    continue;
                }
                Route r = smooth(raw, dyn, !portals.isEmpty(), startExemptRadius,
                        startOut);
                r = align(r, dyn, !portals.isEmpty(), startExemptRadius, startOut);
                r = mergeShortBends(r, dyn, !portals.isEmpty(), startExemptRadius);
                if (!simple(r.points)) {
                    r = raw;
                }
                r.relaxedApproach = relaxed;
                return new Result(r, null, expansions);
            }
            if (++expansions > MAX_EXPANSIONS) {
                return new Result(null, "превышен лимит шагов поиска (" + MAX_EXPANSIONS + ")", expansions);
            }
            Coordinate c = nodes.get(s.cur);
            Coordinate p = s.prev >= 0 ? nodes.get(s.prev) : null;
            boolean fromStart = s.parent == null && portals.isEmpty() && startExemptRadius > 0;
            // окончание трассы
            for (Goal g : goals.near(c)) {
                if (p != null && !turnOk(p, c, g.point) || !g.branchTurnOk(c)
                        || s.parent == null && portals.isEmpty() && !startTurnOk(g.point, c, startOut)) {
                    continue;
                }
                if (!pathExtensionSimple(s, g.point, nodes)) {
                    continue;
                }
                SegmentChecker.Ok ok = checker.check(c, g.point, Collections.emptySet(), g.exemptPoint);
                if (ok == null || !dyn.free(field.segment(c, g.point), g.point, g.dynExemptRadius,
                        fromStart ? c : null, startExemptRadius)) {
                    continue;
                }
                int gi = nodes.size();
                nodes.add(g.point);
                goalNodes.put(gi, g);
                double cost = s.g + costOf(ok) + 0.7 * g.cost / 25_000_000.0;
                State ns = new State(s.cur, gi, cost, s, ok);
                ns.f = cost;
                states.put(key(s.cur, gi), ns);
                open.add(ns);
            }
            // переходы по графу видимости
            for (Edge e : edgesFrom(s.cur, c, staticCount)) {
                Coordinate n = nodes.get(e.to);
                if (p != null && !turnOk(p, c, n) || s.parent == null && portals.isEmpty() && !startTurnOk(n, c, startOut)) {
                    continue;
                }
                if (fromStart ? !dyn.free(field.segment(c, n), c, startExemptRadius) : !dyn.free(field.segment(c, n))) {
                    continue;
                }
                double g = s.g + costOf(e.ok);
                long k = key(s.cur, e.to);
                State old = states.get(k);
                if (old != null && old.g <= g) {
                    continue;
                }
                if (!pathExtensionSimple(s, n, nodes)) {
                    continue;
                }
                State ns = new State(s.cur, e.to, g, s, e.ok);
                ns.f = g + heuristic(n, goals);
                states.put(k, ns);
                open.add(ns);
            }
        }
        return new Result(null, "допустимая трасса до существующей сети не найдена (поиск в окне вокруг цели; окно "
                + "расширялось)", expansions);
    }

    /** No loop or crossing with the already traversed path; short buffer-rounding chords remain legal. */
    private static boolean pathExtensionSimple(State state, Coordinate next, List<Coordinate> nodes) {
        Coordinate from = nodes.get(state.cur);
        org.locationtech.jts.algorithm.RobustLineIntersector intersection = null;
        // The immediate incoming edge shares 'from' and is already constrained by turnOk.
        for (State ancestor = state.parent; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor.prev < 0) {
                continue;
            }
            Coordinate a = nodes.get(ancestor.prev);
            Coordinate b = nodes.get(ancestor.cur);
            if (!Envelope.intersects(from, next, a, b)) {
                continue;
            }
            if (intersection == null) {
                intersection = new org.locationtech.jts.algorithm.RobustLineIntersector();
            }
            intersection.computeIntersection(from, next, a, b);
            if (intersection.hasIntersection()) {
                return false;
            }
        }
        return true;
    }

    private boolean simple(List<Coordinate> points) {
        return field.gf.createLineString(points.toArray(new Coordinate[0])).isSimple();
    }

    /**
     * Трасса от точки (камеры, без финального подхода) до цели поиска; у старта ослаблена проверка построенных линий,
     * т. к. к этой камере уже примыкают другие участки.
     */
    public Result routeFromNode(Target pseudo, DynamicObstacles dyn, GoalProvider goals, double exemptRadius) {
        return routeFromNode(pseudo, dyn, goals, exemptRadius, Collections.emptyList());
    }

    /** @param startOut соседние вершины участков ниже по потоку, уже примыкающих к стартовой камере (поворот ≤ 90°) */
    public Result routeFromNode(Target pseudo, DynamicObstacles dyn, GoalProvider goals, double exemptRadius,
                                List<Coordinate> startOut) {
        return search(pseudo, Collections.emptyList(), Collections.emptyList(), false, dyn, goals, exemptRadius, startOut);
    }

    /** Поток приходит в старт b из a и в каждую ветку из out поворачивает не больше чем на 90°. */
    private static boolean startTurnOk(Coordinate a, Coordinate b, List<Coordinate> out) {
        for (Coordinate o : out) {
            if (!turnOk(a, b, o)) {
                return false;
            }
        }
        return true;
    }

    /** Диагностика старта поиска: точка выхода из ОКС и причины отказа рёбер из неё. */
    public String explainStart(Target target, List<Restriction> ownOks, DynamicObstacles dyn) {
        Coordinate t = target.point.getCoordinate();
        Coordinate[] p = ownOks.isEmpty() ? new Coordinate[]{t} : portal(t, ownOks, dyn, true);
        String mode = "строгий";
        if (p == null) {
            p = portal(t, ownOks, dyn, false);
            mode = "нестрогий";
        }
        if (p == null) {
            return "выхода нет";
        }
        Coordinate a = p[0];
        int total = 0, tangentOk = 0, roi = 0, block = 0, zone = 0, ok = 0;
        for (int to : field.verticesWithin(a, NEIGHBOUR_RADIUS)) {
            total++;
            Coordinate n = field.vertices.get(to);
            if (!tangent(to, a)) {
                continue;
            }
            tangentOk++;
            LineString seg = field.segment(a, n);
            if (!field.roi.covers(seg)) {
                roi++;
            } else if (field.blockUnion.intersects(seg)) {
                block++;
            } else if (checker.check(a, n, Collections.emptySet(), null) == null) {
                zone++;
            } else {
                ok++;
            }
        }
        return String.format("выход (%s) %.2f %.2f, blocked=%s, zone=%s; вершин в радиусе %d, касательных %d: вне ROI %d, "
                + "через запрет %d, спецзоны %d, допустимо %d", mode, a.x, a.y, field.blocked(a), field.insideZone(a),
                total, tangentOk, roi, block, zone, ok);
    }

    private double heuristic(Coordinate c, GoalProvider goals) {
        return goals.lowerBoundDistance(c) * weight(1);
    }

    /** Рёбра из вершины: для статических — из кэша, для старта — на лету. */
    private List<Edge> edgesFrom(int idx, Coordinate c, int staticCount) {
        if (idx < staticCount) {
            return visibility.computeIfAbsent(idx, i -> computeEdges(i, c, staticCount));
        }
        return computeEdges(-1, c, staticCount);
    }

    private List<Edge> computeEdges(int from, Coordinate c, int staticCount) {
        List<Edge> out = new ArrayList<>();
        for (int to : field.verticesWithin(c, NEIGHBOUR_RADIUS)) {
            if (to == from) {
                continue;
            }
            Coordinate n = field.vertices.get(to);
            if (!tangent(to, c) || (from >= 0 && !tangent(from, n))) {
                continue;
            }
            SegmentChecker.Ok ok = checker.check(c, n, Collections.emptySet(), null);
            if (ok != null) {
                out.add(new Edge(to, ok));
            }
        }
        return out;
    }

    /** Ребро касается контура в вершине idx: соседи вершины по контуру лежат по одну сторону от прямой. */
    private boolean tangent(int idx, Coordinate other) {
        Coordinate v = field.vertices.get(idx);
        Coordinate[] nb = field.ringNeighbours.get(idx);
        int o1 = Orientation.index(other, v, nb[0]);
        int o2 = Orientation.index(other, v, nb[1]);
        return o1 * o2 >= 0;
    }

    /**
     * Начало финального прямого участка (§2.2). Строго: луч от цели через ближайшую точку внешнего контура
     * своей части ОКС. Нестрого (если строгий выход закрыт): ближайшая точка контура, от которой отрезок до цели
     * идёт внутри своего ОКС, а наружу есть свободный прямой выход. @return {точка выхода} или null.
     */
    static final int MAX_RELAXED_PORTALS = 16;
    /** Нестрогий выход: участок внутри своего ОКС длиннее ближайшего не больше чем на столько, м. */
    static final double RELAXED_MAX_EXTRA = 60.0;
    /** Сначала пробуются выходы, у которых участок внутри своего ОКС длиннее ближайшего не больше чем на столько, м. */
    static final double RELAXED_PREFERRED_EXTRA = 10.0;

    /** Причина неподключения: строгий выход закрыт по входным данным (зависит только от своего ОКС и соседей). */
    public static final String STRICT_EXIT_CLOSED = "строгий финальный подход закрыт по входным данным (§2.2)";

    /** Финальный участок по строгому лучу прямо до места присоединения на нём (одна прямая «цель → камера»). */
    private Result directOnRay(Target target, List<Restriction> own, DynamicObstacles dyn, GoalProvider goals) {
        Coordinate t = target.point.getCoordinate();
        org.locationtech.jts.geom.Point tp = field.gf.createPoint(t);
        List<Geometry> shells = new ArrayList<>();
        List<Geometry> parts = new ArrayList<>();
        for (Restriction r : own) {
            parts.add(r.geometry);
            for (int i = 0; i < r.geometry.getNumGeometries(); i++) {
                Geometry g = r.geometry.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.Polygon && g.covers(tp)) {
                    shells.add(((org.locationtech.jts.geom.Polygon) g).getExteriorRing());
                }
            }
        }
        if (shells.isEmpty()) {
            return null;
        }
        Coordinate pb = strictBoundaryPoint(tp, own, UnaryUnionOp.union(shells));
        double d = pb.distance(t);
        if (d < 1e-6) {
            return null;
        }
        double ux = (pb.x - t.x) / d;
        double uy = (pb.y - t.y) / d;
        Coordinate start = new Coordinate(pb.x + ux * 0.01, pb.y + uy * 0.01);
        Coordinate far = new Coordinate(pb.x + ux * MAX_APPROACH, pb.y + uy * MAX_APPROACH);
        Geometry ownUnion = UnaryUnionOp.union(parts);
        Set<Restriction> ignored = new HashSet<>(own);
        Route best = null;
        for (Goal g : goals.onSegment(start, far)) {
            if (ownUnion.intersects(field.segment(start, g.point))) {
                continue; // по пути к точке луч снова заходит в свой ОКС
            }
            if (!g.branchTurnOk(t)) {
                continue;
            }
            SegmentChecker.Ok ok = checker.check(t, g.point, ignored, g.exemptPoint);
            if (ok == null || !dyn.free(field.segment(t, g.point), g.point, g.dynExemptRadius)) {
                continue;
            }
            double cost = costOf(ok) + 0.7 * g.cost / 25_000_000.0;
            if (best == null || cost < best.scoreCost) {
                List<Coordinate> pts = new ArrayList<>();
                pts.add(t);
                pts.add(g.point);
                List<List<SpecialSpan>> spans = new ArrayList<>();
                spans.add(ok.spans);
                best = new Route(pts, spans, g, ok.length, cost);
            }
        }
        return best == null ? null : new Result(best, null, 0);
    }

    /** Точки выхода: строгая (одна) или до 16 нестрогих в разных направлениях. */
    private List<Coordinate> portals(Coordinate t, List<Restriction> own, DynamicObstacles dyn, boolean strict) {
        if (strict) {
            Coordinate[] p = portal(t, own, dyn, true);
            return p == null ? Collections.emptyList() : Collections.singletonList(p[0]);
        }
        return relaxedPortals(t, own, dyn);
    }

    private Coordinate[] portal(Coordinate t, List<Restriction> own, DynamicObstacles dyn, boolean strict) {
        List<Coordinate> out = portalsImpl(t, own, dyn, strict, 1, RELAXED_MAX_EXTRA, false);
        return out.isEmpty() ? null : new Coordinate[]{out.get(0)};
    }

    private List<Coordinate> relaxedPortals(Coordinate t, List<Restriction> own, DynamicObstacles dyn) {
        return relaxedPortals(t, own, dyn, RELAXED_MAX_EXTRA, false);
    }

    /** @param clean только выходы, после которых луч снаружи не приближается к своему зданию (см. ray) */
    private List<Coordinate> relaxedPortals(Coordinate t, List<Restriction> own, DynamicObstacles dyn, double maxExtra,
                                            boolean clean) {
        return portalsImpl(t, own, dyn, false, MAX_RELAXED_PORTALS, maxExtra, clean);
    }

    /** Нестрогие выходы берутся по возрастанию расстояния, направления различаются хотя бы на 10°. */
    private List<Coordinate> portalsImpl(Coordinate t, List<Restriction> own, DynamicObstacles dyn, boolean strict, int limit,
                                         double maxExtra, boolean clean) {
        List<Coordinate> found = new ArrayList<>();
        List<Geometry> shells = new ArrayList<>();
        List<Geometry> parts = new ArrayList<>();
        List<Geometry> containing = new ArrayList<>();
        org.locationtech.jts.geom.Point tp = field.gf.createPoint(t);
        for (Restriction r : own) {
            parts.add(r.geometry);
            for (int i = 0; i < r.geometry.getNumGeometries(); i++) {
                Geometry g = r.geometry.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.Polygon && g.covers(tp)) {
                    shells.add(((org.locationtech.jts.geom.Polygon) g).getExteriorRing());
                    containing.add(g);
                }
            }
        }
        if (shells.isEmpty()) {
            if (Boolean.getBoolean("heatnet.debug")) {
                log.info("  выход: цель не внутри своих частей ОКС ({} частей)", own.size());
            }
            return found;
        }
        // ближайшая граница — внешний контур части с целью (двор-«дырка» наружу не выводит), DECISIONS №26
        Geometry exterior = UnaryUnionOp.union(shells);
        Geometry ownUnion = UnaryUnionOp.union(parts);
        Geometry containingUnion = UnaryUnionOp.union(containing);
        Set<Restriction> ignored = new HashSet<>(own);
        Coordinate pb = strict ? strictBoundaryPoint(tp, own, exterior) : DistanceOp.nearestPoints(exterior, tp)[0];
        if (strict) {
            Coordinate[] a = ray(t, pb, ignored, ownUnion, dyn, false);
            if (a != null) {
                found.add(a[0]);
            }
            return found;
        }
        double nearest = pb.distance(t);
        List<Coordinate> candidates = new ArrayList<>();
        for (int i = 0; i < exterior.getNumGeometries(); i++) {
            Coordinate[] cs = exterior.getGeometryN(i).getCoordinates();
            for (int k = 0; k + 1 < cs.length; k++) {
                double len = cs[k].distance(cs[k + 1]);
                int steps = Math.max(1, (int) Math.ceil(len / 0.5));
                for (int j = 0; j < steps; j++) {
                    double f = (double) j / steps;
                    candidates.add(new Coordinate(cs[k].x + (cs[k + 1].x - cs[k].x) * f, cs[k].y + (cs[k + 1].y - cs[k].y) * f));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a.distance(t), b.distance(t)));
        int tries = 0;
        List<Double> angles = new ArrayList<>();
        Geometry containingBuf = containingUnion.buffer(1e-6);
        for (Coordinate c : candidates) {
            if (c.distance(t) > nearest + maxExtra || ++tries > 1500 || found.size() >= limit) {
                break;
            }
            double ang = Math.toDegrees(Math.atan2(c.y - t.y, c.x - t.x));
            boolean similar = false;
            for (double a : angles) {
                double d = Math.abs(a - ang) % 360;
                if (Math.min(d, 360 - d) < 10) {
                    similar = true;
                    break;
                }
            }
            if (similar || c.distance(t) < 1e-6 || !containingBuf.covers(field.segment(t, c))) {
                continue;
            }
            Coordinate[] a = ray(t, c, ignored, ownUnion, dyn, clean);
            if (a != null) {
                found.add(a[0]);
                angles.add(ang);
            }
        }
        return found;
    }

    /**
     * Отличается ли строгий луч по текущей трактовке от дословного §2.2: глобально ближайшая точка границы части
     * с целью лежит на стене двора, а луч идёт от внешнего контура. Такая цель тоже попадает в
     * diag_relaxed_final_approach — отступление от дословного прочтения раскрывается для всех целей.
     */
    private boolean nonLiteral(Coordinate t, List<Restriction> own) {
        if (TerminalPolicy.current() == TerminalPolicy.LITERAL) {
            return false;
        }
        org.locationtech.jts.geom.Point tp = field.gf.createPoint(t);
        double exterior = Double.POSITIVE_INFINITY;
        double all = Double.POSITIVE_INFINITY;
        for (Restriction r : own) {
            for (int i = 0; i < r.geometry.getNumGeometries(); i++) {
                Geometry g = r.geometry.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.Polygon && g.covers(tp)) {
                    exterior = Math.min(exterior, ((org.locationtech.jts.geom.Polygon) g).getExteriorRing().distance(tp));
                    all = Math.min(all, g.getBoundary().distance(tp));
                }
            }
        }
        return all < exterior - 1e-6;
    }

    /**
     * Ближайшая точка границы для строгого луча по трактовке §2.2 ({@link TerminalPolicy}):
     * LITERAL — вся граница частей своего ОКС с целью, включая дворы; иначе — внешний контур.
     */
    private Coordinate strictBoundaryPoint(org.locationtech.jts.geom.Point tp, List<Restriction> own, Geometry exterior) {
        if (TerminalPolicy.current() != TerminalPolicy.LITERAL) {
            return DistanceOp.nearestPoints(exterior, tp)[0];
        }
        List<Geometry> bounds = new ArrayList<>();
        for (Restriction r : own) {
            for (int i = 0; i < r.geometry.getNumGeometries(); i++) {
                Geometry g = r.geometry.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.Polygon && g.covers(tp)) {
                    bounds.add(g.getBoundary());
                }
            }
        }
        return DistanceOp.nearestPoints(bounds.isEmpty() ? exterior : UnaryUnionOp.union(bounds), tp)[0];
    }

    /** Строгий луч целиком (до {@link #MAX_APPROACH} за границей) внутри окон поиска: точки луча не пропускались. */
    boolean strictRayInsideRoi(Coordinate t, List<Restriction> own) {
        org.locationtech.jts.geom.Point tp = field.gf.createPoint(t);
        List<Geometry> shells = new ArrayList<>();
        for (Restriction r : own) {
            for (int i = 0; i < r.geometry.getNumGeometries(); i++) {
                Geometry g = r.geometry.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.Polygon && g.covers(tp)) {
                    shells.add(((org.locationtech.jts.geom.Polygon) g).getExteriorRing());
                }
            }
        }
        if (shells.isEmpty()) {
            return false;
        }
        Coordinate pb = strictBoundaryPoint(tp, own, UnaryUnionOp.union(shells));
        double d = pb.distance(t);
        if (d < 1e-6) {
            return false;
        }
        Coordinate far = new Coordinate(pb.x + (pb.x - t.x) / d * MAX_APPROACH, pb.y + (pb.y - t.y) / d * MAX_APPROACH);
        return field.insideRoi(field.segment(t, far));
    }

    /** Точка на луче «цель → точка контура» за пределами зон запрета, до которой отрезок от цели допустим. */
    /** Насколько луч снаружи может снова приблизиться к своему зданию в «чистом» выходе (погрешность), м. */
    static final double CLEAN_EXIT_TOL = 0.05;

    /**
     * @param clean «чистый» выход: снаружи расстояние от луча до своего здания не уменьшается — финальный участок
     *              не идёт вдоль другого крыла того же здания внутри его зоны отступа
     */
    private Coordinate[] ray(Coordinate t, Coordinate pb, Set<Restriction> ignored, Geometry ownUnion, DynamicObstacles dyn,
                             boolean clean) {
        double dx = pb.x - t.x;
        double dy = pb.y - t.y;
        double d = Math.hypot(dx, dy);
        if (d < 1e-6) {
            if (Boolean.getBoolean("heatnet.debug")) {
                log.info("  луч: цель на границе своего ОКС");
            }
            return null; // цель на границе: направление наружу не определено (в наборе не встречается)
        }
        dx /= d;
        dy /= d;
        if (Boolean.getBoolean("heatnet.debug")) {
            log.info("  луч: от {} направление ({}, {}), ДУ поля {}", pb, dx, dy, field.dn);
        }
        org.locationtech.jts.geom.prep.PreparedGeometry ownPrepared =
                org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(ownUnion);
        Coordinate out = new Coordinate(pb.x + dx * 0.01, pb.y + dy * 0.01);
        // идём по лучу, пока отрезок «цель → точка» не станет допустимым (дорога перед домом пересекается целиком)
        boolean debug = Boolean.getBoolean("heatnet.debug");
        double ownDist = 0;
        for (double s = APPROACH_STEP; s <= MAX_APPROACH; s += APPROACH_STEP) {
            Coordinate a = new Coordinate(pb.x + dx * s, pb.y + dy * s);
            if (ownPrepared.intersects(field.segment(out, a))) {
                if (debug) {
                    log.info("  луч: на {} м снова входит в свой ОКС", s);
                }
                return null; // снаружи луч снова заходит в свой ОКС: финальный участок пересекал бы его дважды
            }
            if (clean) {
                double dOwn = ownUnion.distance(field.gf.createPoint(a));
                if (dOwn < ownDist - CLEAN_EXIT_TOL) {
                    return null; // луч снова приближается к своему зданию: идёт вдоль другого крыла
                }
                ownDist = Math.max(ownDist, dOwn);
            }
            if (field.blocked(a) || !field.insideRoi(a)) {
                continue;
            }
            LineString seg = field.segment(t, a);
            // у построенных веток проверяем только оси: если выход упёрся в габарит ветки,
            // дальше поиск присоединится к ней самой (обычные рёбра габарит проверяют)
            boolean staticOk = checker.check(t, a, ignored, null) != null;
            boolean axesOk = dyn.freeOfAxes(seg);
            if (staticOk && axesOk) {
                return new Coordinate[]{a};
            }
            if (!field.insideZone(a)) {
                if (debug) {
                    log.info("  луч: первая свободная точка на {} м, отрезок допустим по входу={}, не пересекает оси веток={}",
                            s, staticOk, axesOk);
                }
                return null; // первая свободная точка луча недоступна — дальше по лучу отрезок только длиннее
            }
        }
        if (debug) {
            log.info("  луч: за {} м свободной точки нет (ДУ {})", MAX_APPROACH, field.dn);
        }
        return null;
    }

    /**
     * Выпрямление: убираем промежуточные вершины, если прямой отрезок между соседями допустим, а повороты
     * остаются ≤ 90° — это снимает мелкие изломы на скруглениях буферов (§2.1: без необоснованных изломов).
     * Точка выхода из своего ОКС (второй узел) сохраняется: через неё идёт финальный прямой участок.
     */
    private Route smooth(Route r, DynamicObstacles dyn, boolean hasPortal, double startExemptRadius,
                         List<Coordinate> startOut) {
        List<Coordinate> pts = r.points;
        int n = pts.size();
        int fixed = hasPortal ? 2 : 1;
        if (n <= fixed + 1) {
            return r;
        }
        List<Coordinate> out = new ArrayList<>(pts.subList(0, fixed));
        List<List<SpecialSpan>> spans = new ArrayList<>();
        List<SegmentChecker.Ok> oks = new ArrayList<>();
        if (hasPortal) {
            spans.add(r.spans.get(0));
        }
        int j = fixed;
        while (j < n) {
            Coordinate anchor = out.get(out.size() - 1);
            Coordinate prev = out.size() >= 2 ? out.get(out.size() - 2) : null;
            // самая дальняя точка, до которой можно дойти прямо от якоря
            int best = j;
            SegmentChecker.Ok bestOk = null;
            for (int k = n - 1; k >= j; k--) {
                Coordinate q = pts.get(k);
                boolean last = k == n - 1;
                if (prev != null && turn(prev, anchor, q) > MAX_TURN_DEG + 1e-9) {
                    continue;
                }
                if (k + 1 < n && turn(anchor, q, pts.get(k + 1)) > MAX_TURN_DEG + 1e-9) {
                    continue;
                }
                if (last && r.goal != null && !r.goal.branchTurnOk(anchor)) {
                    continue;
                }
                if (!hasPortal && out.size() == 1 && !startTurnOk(q, anchor, startOut)) {
                    continue;
                }
                SegmentChecker.Ok ok = checker.check(anchor, q, Collections.emptySet(), last ? r.goal.exemptPoint : null);
                if (ok == null) {
                    continue;
                }
                boolean atStart = !hasPortal && out.size() == 1 && startExemptRadius > 0;
                boolean free = dyn.free(field.segment(anchor, q), last ? q : null, last ? r.goal.dynExemptRadius : 0,
                        atStart ? anchor : null, startExemptRadius);
                if (!free) {
                    continue;
                }
                best = k;
                bestOk = ok;
                break;
            }
            if (bestOk == null) {
                return r; // не удалось перепроверить — оставляем найденную трассу как есть
            }
            out.add(pts.get(best));
            spans.add(bestOk.spans);
            oks.add(bestOk);
            j = best + 1;
        }
        double length = 0;
        double cost = 0.7 * r.goal.cost / 25_000_000.0;
        if (hasPortal) {
            double l0 = pts.get(0).distance(pts.get(1));
            length += l0;
            cost += costOf(new SegmentChecker.Ok(l0, r.spans.get(0)));
        }
        for (SegmentChecker.Ok ok : oks) {
            length += ok.length;
            cost += costOf(ok);
        }
        if (!simple(out)) {
            return r;
        }
        Route s = new Route(out, spans, r.goal, length, cost);
        return s;
    }

    /* ---------------- выравнивание по локальным осям застройки ---------------- */

    /** Отрезки короче не выравниваются, м. */
    static final double ALIGN_MIN_SEGMENT = 15.0;
    /** Катеты короче не строятся: без мелких изломов и ступенек (разъяснения 29.09, №18), м. */
    static final double ALIGN_MIN_LEG = 5.0;
    /** Выравниваются только явные диагонали; малое отклонение через Г дало бы ступеньку, град. */
    static final double ALIGN_TOLERANCE_DEG = 10.0;
    /** Между соседними поворотами в разные стороны — не короче, иначе это пила, м. */
    static final double ALIGN_MIN_JOG = 15.0;
    /** Зигзаг по маршруту: повороты в разные стороны с отрезком между ними короче, м. */
    static final double ALIGN_ZIGZAG_LEG = 30.0;
    /** Насколько Г может увеличить сумму поворотов: один прямой угол и половина второго, град. */
    static final double ALIGN_MAX_ADDED_TURN = 135.0;
    /** Угол Г не дальше этого от здания или дороги: трасса идёт вдоль фасада, а не крюком по пустому месту, м. */
    static final double ALIGN_SUPPORT = 30.0;
    /** На сколько можно удлинить маршрут ради выравнивания, доля его длины. */
    static final double ALIGN_MAX_EXTRA = 0.25;
    /** Радиус, в котором стены зданий и границы дорог задают локальные оси, м. */
    static final double AXIS_RADIUS = 60.0;
    private static final double AXIS_CELL = 25.0;
    private final Map<Long, Double> axisCache = new HashMap<>();

    /**
     * Включено ли выравнивание: {@code heatnet.align} или {@code HEATNET_ALIGN} = true | false (по умолчанию true).
     * false — прежняя геометрия (кратчайшие допустимые трассы), для сравнения.
     */
    public static boolean alignEnabled() {
        String v = System.getProperty("heatnet.align");
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv("HEATNET_ALIGN");
        }
        return v == null || v.trim().isEmpty() || !"false".equalsIgnoreCase(v.trim());
    }

    /**
     * Трассы вдоль застройки (замечание экспертов): диагональный отрезок (от {@link #ALIGN_MIN_SEGMENT} м, отклонение
     * от локальной оси от {@link #ALIGN_TOLERANCE_DEG}°) заменяется ломаной вдоль локальных осей зданий и дорог —
     * «Г» или «Z» с перескоком посередине двора. Замена принимается, только если все новые отрезки проходят те же
     * проверки, что и найденная трасса (ограничения и отступы, уже построенные линии, повороты ≤ 90°, подход к месту
     * присоединения), не пересекают объекты спецпрохода, катеты не короче {@link #ALIGN_MIN_LEG} м, угол ломаной рядом
     * со зданием или дорогой, нет «пилы» и «горбов», а маршрут удлиняется не больше чем на {@link #ALIGN_MAX_EXTRA}.
     * Если по всему маршруту зигзагов стало больше, остаётся исходная трасса. Финальный прямой участок к цели (§2.2)
     * не меняется. Формула S не меняется: выровненная трасса просто длиннее и дороже, это видно в показателях.
     */
    Route align(Route r, DynamicObstacles dyn, boolean hasPortal, double startExemptRadius) {
        return align(r, dyn, hasPortal, startExemptRadius, Collections.emptyList());
    }

    /** @param startOut см. {@link #routeFromNode(Target, DynamicObstacles, GoalProvider, double, List)} */
    Route align(Route r, DynamicObstacles dyn, boolean hasPortal, double startExemptRadius, List<Coordinate> startOut) {
        if (r.goal == null || !alignEnabled()) {
            return r;
        }
        List<Coordinate> pts = new ArrayList<>(r.points);
        List<List<SpecialSpan>> spans = new ArrayList<>(r.spans);
        double budget = ALIGN_MAX_EXTRA * r.length;
        double extra = 0;
        boolean changed = false;
        for (int i = hasPortal ? 1 : 0; i + 1 < pts.size(); i++) {
            Coordinate a = pts.get(i);
            Coordinate b = pts.get(i + 1);
            double len = a.distance(b);
            if (len < ALIGN_MIN_SEGMENT || !spans.get(i).isEmpty()) {
                continue;
            }
            double axis = localAxisDeg(new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2));
            if (Double.isNaN(axis)) {
                continue;
            }
            double dir = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
            double dev = ((dir - axis) % 90 + 90) % 90;
            if (Math.min(dev, 90 - dev) < ALIGN_TOLERANCE_DEG) {
                continue;
            }
            boolean last = i + 2 == pts.size();
            Coordinate prev = i > 0 ? pts.get(i - 1) : null;
            Coordinate next = last ? null : pts.get(i + 2);
            double ux = Math.cos(Math.toRadians(axis));
            double uy = Math.sin(Math.toRadians(axis));
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double pu = dx * ux + dy * uy;
            double pv = -dx * uy + dy * ux;
            // кандидаты: Г (сначала вдоль одной оси, потом вдоль другой) и Z с перескоком посередине двора
            List<Coordinate[]> shapes = new ArrayList<>();
            shapes.add(new Coordinate[]{new Coordinate(a.x + pu * ux, a.y + pu * uy)});
            shapes.add(new Coordinate[]{new Coordinate(a.x - pv * uy, a.y + pv * ux)});
            for (double t : new double[]{0.5, 0.25, 0.75}) {
                Coordinate u1 = new Coordinate(a.x + t * pu * ux, a.y + t * pu * uy);
                shapes.add(new Coordinate[]{u1, new Coordinate(u1.x - pv * uy, u1.y + pv * ux)});
                Coordinate v1 = new Coordinate(a.x - t * pv * uy, a.y + t * pv * ux);
                shapes.add(new Coordinate[]{v1, new Coordinate(v1.x + pu * ux, v1.y + pu * uy)});
            }
            Coordinate[] best = null;
            List<SegmentChecker.Ok> bestOks = null;
            double bestAdd = 0;
            for (Coordinate[] corners : shapes) {
                if (!hasPortal && i == 0 && !startTurnOk(corners[0], a, startOut)) {
                    continue; // новый первый отрезок развернул бы поток в уже примыкающие ветки больше чем на 90°
                }
                List<SegmentChecker.Ok> oks = alignShape(r, dyn, a, b, corners, prev, next, last,
                        !hasPortal && i == 0 && startExemptRadius > 0, startExemptRadius);
                if (oks == null) {
                    continue;
                }
                double add = -len;
                for (SegmentChecker.Ok ok : oks) {
                    add += ok.length;
                }
                if (extra + add > budget) {
                    continue;
                }
                List<Coordinate> trial = new ArrayList<>(pts);
                for (int k = 0; k < corners.length; k++) {
                    trial.add(i + 1 + k, corners[k]);
                }
                if (!selfClear(trial, i, i + corners.length)) {
                    continue; // новые отрезки пересекли бы или задели свою же трассу
                }
                best = corners;
                bestOks = oks;
                bestAdd = add;
                break; // Г раньше Z, Z с перескоком посередине раньше остальных
            }
            if (best == null) {
                continue;
            }
            for (int k = 0; k < best.length; k++) {
                pts.add(i + 1 + k, best[k]);
            }
            spans.set(i, bestOks.get(0).spans);
            for (int k = 1; k < bestOks.size(); k++) {
                spans.add(i + k, bestOks.get(k).spans);
            }
            extra += bestAdd;
            changed = true;
            i += best.length; // новые отрезки уже вдоль осей
        }
        if (!changed) {
            return r;
        }
        // по всему маршруту зигзагов не больше, чем было, и маршрут без самопересечений: иначе исходная трасса
        if (zigzags(pts) > zigzags(r.points) || !field.gf.createLineString(pts.toArray(new Coordinate[0])).isSimple()) {
            return r;
        }
        double length = 0;
        double cost = 0.7 * r.goal.cost / 25_000_000.0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double l = pts.get(i).distance(pts.get(i + 1));
            length += l;
            cost += costOf(new SegmentChecker.Ok(l, spans.get(i)));
        }
        Route out = new Route(pts, spans, r.goal, length, cost);
        out.relaxedApproach = r.relaxedApproach;
        return out;
    }

    /**
     * Проверка ломаной a → corners → b вместо отрезка a → b теми же правилами, что и найденная трасса.
     * @return допустимости новых отрезков по порядку или null
     */
    private List<SegmentChecker.Ok> alignShape(Route r, DynamicObstacles dyn, Coordinate a, Coordinate b, Coordinate[] corners,
                                               Coordinate prev, Coordinate next, boolean last, boolean atStart,
                                               double startExemptRadius) {
        List<Coordinate> path = new ArrayList<>();
        path.add(a);
        Collections.addAll(path, corners);
        path.add(b);
        for (int k = 0; k + 1 < path.size(); k++) {
            if (path.get(k).distance(path.get(k + 1)) < ALIGN_MIN_LEG) {
                return null;
            }
        }
        double before = (prev != null ? turn(prev, a, b) : 0) + (next != null ? turn(a, b, next) : 0);
        double after = 0;
        List<Coordinate> wide = new ArrayList<>();
        if (prev != null) {
            wide.add(prev);
        }
        wide.addAll(path);
        if (next != null) {
            wide.add(next);
        }
        for (int k = 0; k + 2 < wide.size(); k++) {
            double t = turn(wide.get(k), wide.get(k + 1), wide.get(k + 2));
            if (t > MAX_TURN_DEG + 1e-9) {
                return null;
            }
            after += t;
        }
        // без пилы: соседние повороты в разные стороны с коротким отрезком между ними (разъяснения 29.09, №18)
        for (int k = 0; k + 3 < wide.size(); k++) {
            double s1 = cross(wide.get(k), wide.get(k + 1), wide.get(k + 2));
            double s2 = cross(wide.get(k + 1), wide.get(k + 2), wide.get(k + 3));
            boolean bent1 = turn(wide.get(k), wide.get(k + 1), wide.get(k + 2)) > 5;
            boolean bent2 = turn(wide.get(k + 1), wide.get(k + 2), wide.get(k + 3)) > 5;
            if (bent1 && bent2 && s1 * s2 < 0 && wide.get(k + 1).distance(wide.get(k + 2)) < ALIGN_MIN_JOG) {
                return null;
            }
        }
        // каждый новый угол добавляет прямой угол; если сверх этого растут соседние повороты — это «горб»
        if (after - before > corners.length * MAX_TURN_DEG + ALIGN_MAX_ADDED_TURN - MAX_TURN_DEG + 1e-6) {
            return null;
        }
        if (last && !r.goal.branchTurnOk(corners[corners.length - 1])) {
            return null;
        }
        for (Coordinate c : corners) {
            if (!supported(c)) {
                return null;
            }
        }
        List<SegmentChecker.Ok> oks = new ArrayList<>();
        for (int k = 0; k + 1 < path.size(); k++) {
            boolean end = last && k + 2 == path.size();
            Coordinate p = path.get(k);
            Coordinate q = path.get(k + 1);
            SegmentChecker.Ok ok = checker.check(p, q, Collections.emptySet(), end ? r.goal.exemptPoint : null);
            if (ok == null || !ok.spans.isEmpty()) {
                return null;
            }
            boolean first = k == 0 && atStart;
            if (!dyn.free(field.segment(p, q), end ? q : null, end ? r.goal.dynExemptRadius : 0,
                    first ? p : null, first ? startExemptRadius : 0)) {
                return null;
            }
            oks.add(ok);
        }
        return oks;
    }

    /** Отрезок между двумя поворотами короче — мелкий излом (разъяснения №5; доп. разъяснения 29.09, №18), м. */
    static final double MIN_BEND_SEGMENT = 1.0;
    /** Насколько далеко от исходных вершин может оказаться объединённая вершина, м. */
    static final double BEND_MERGE_REACH = 3.0;

    /**
     * Мелкие изломы на скруглениях обхода — две вершины с поворотами в одну сторону и отрезком короче
     * {@link #MIN_BEND_SEGMENT} между ними — заменяются одной вершиной на пересечении продолжений соседних отрезков.
     * Направления соседних отрезков не меняются, поэтому повороты на концах трассы, в камерах и у цели те же;
     * новая вершина лежит снаружи скругления, дальше от обходимого препятствия. Замена проверяется теми же правилами,
     * что и трасса (ограничения и отступы, построенные линии, поворот ≤ 90°, без самопересечений); иначе — как было.
     * Финальный участок к цели (§2.2) и спецучастки не меняются.
     */
    Route mergeShortBends(Route r, DynamicObstacles dyn, boolean hasPortal, double startExemptRadius) {
        if (r.goal == null) {
            return r;
        }
        List<Coordinate> pts = new ArrayList<>(r.points);
        List<List<SpecialSpan>> spans = new ArrayList<>(r.spans);
        boolean changed = false;
        for (int k = hasPortal ? 1 : 0; k + 3 < pts.size(); k++) {
            Coordinate a = pts.get(k);
            Coordinate b = pts.get(k + 1);
            Coordinate c = pts.get(k + 2);
            Coordinate d = pts.get(k + 3);
            if (b.distance(c) >= MIN_BEND_SEGMENT
                    || !spans.get(k).isEmpty() || !spans.get(k + 1).isEmpty() || !spans.get(k + 2).isEmpty()) {
                continue;
            }
            if (cross(a, b, c) * cross(b, c, d) <= 0) {
                continue; // повороты в разные стороны — это не скругление
            }
            Coordinate x = lineIntersection(a, b, c, d);
            if (x == null || x.distance(b) > BEND_MERGE_REACH || x.distance(c) > BEND_MERGE_REACH
                    || (b.x - a.x) * (x.x - b.x) + (b.y - a.y) * (x.y - b.y) < 0
                    || (c.x - d.x) * (x.x - c.x) + (c.y - d.y) * (x.y - c.y) < 0
                    || turn(a, x, d) > MAX_TURN_DEG + 1e-9) {
                continue;
            }
            boolean last = k + 3 == pts.size() - 1;
            boolean atStart = !hasPortal && k == 0 && startExemptRadius > 0;
            SegmentChecker.Ok ok1 = checker.check(a, x, Collections.emptySet(), null);
            SegmentChecker.Ok ok2 = checker.check(x, d, Collections.emptySet(), last ? r.goal.exemptPoint : null);
            if (ok1 == null || ok2 == null || !ok1.spans.isEmpty() || !ok2.spans.isEmpty()
                    || !dyn.free(field.segment(a, x), null, 0, atStart ? a : null, atStart ? startExemptRadius : 0)
                    || !dyn.free(field.segment(x, d), last ? d : null, last ? r.goal.dynExemptRadius : 0, null, 0)) {
                continue;
            }
            List<Coordinate> trial = new ArrayList<>(pts);
            trial.set(k + 1, x);
            trial.remove(k + 2);
            if (!selfClear(trial, k, k + 1)) {
                continue; // объединённая вершина свела бы трассу саму с собой
            }
            pts = trial;
            spans.set(k, ok1.spans);
            spans.set(k + 1, ok2.spans);
            spans.remove(k + 2);
            changed = true;
        }
        if (!changed) {
            return r;
        }
        double length = 0;
        double cost = 0.7 * r.goal.cost / 25_000_000.0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double l = pts.get(i).distance(pts.get(i + 1));
            length += l;
            cost += costOf(new SegmentChecker.Ok(l, spans.get(i)));
        }
        Route out = new Route(pts, spans, r.goal, length, cost);
        out.relaxedApproach = r.relaxedApproach;
        return out;
    }

    /** Пересечение прямых ab и cd или null, если они параллельны. */
    static Coordinate lineIntersection(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
        double x1 = b.x - a.x;
        double y1 = b.y - a.y;
        double x2 = d.x - c.x;
        double y2 = d.y - c.y;
        double den = x1 * y2 - y1 * x2;
        if (Math.abs(den) < 1e-12 * Math.hypot(x1, y1) * Math.hypot(x2, y2)) {
            return null;
        }
        double t = ((c.x - a.x) * y2 - (c.y - a.y) * x2) / den;
        return new Coordinate(a.x + t * x1, a.y + t * y1);
    }

    /** Зазор между габаритами двух частей одной трассы — как между двумя новыми линиями, м. */
    static final double SELF_GAP = 0.05;

    /**
     * Маршрут pts без самопересечений, а отрезки first..last (новые) не ближе ширины пары труб плюс зазор к
     * несоседним отрезкам того же маршрута: выравнивание не должно сводить трассу саму с собой.
     */
    boolean selfClear(List<Coordinate> pts, int first, int last) {
        if (!field.gf.createLineString(pts.toArray(new Coordinate[0])).isSimple()) {
            return false;
        }
        double need = 2 * field.halfWidth + SELF_GAP;
        for (int k = first; k <= last && k + 1 < pts.size(); k++) {
            LineString seg = field.segment(pts.get(k), pts.get(k + 1));
            for (int j = 0; j + 1 < pts.size(); j++) {
                if (Math.abs(j - k) <= 1) {
                    continue; // соседние отрезки сходятся в общей вершине
                }
                if (seg.distance(field.segment(pts.get(j), pts.get(j + 1))) < need) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Пары соседних поворотов больше 20° в разные стороны с отрезком между ними короче {@link #ALIGN_ZIGZAG_LEG}. */
    static int zigzags(List<Coordinate> pts) {
        int n = 0;
        for (int k = 0; k + 3 < pts.size(); k++) {
            Coordinate p0 = pts.get(k);
            Coordinate p1 = pts.get(k + 1);
            Coordinate p2 = pts.get(k + 2);
            Coordinate p3 = pts.get(k + 3);
            if (turn(p0, p1, p2) > 20 && turn(p1, p2, p3) > 20 && cross(p0, p1, p2) * cross(p1, p2, p3) < 0
                    && p1.distance(p2) < ALIGN_ZIGZAG_LEG) {
                n++;
            }
        }
        return n;
    }

    private static double cross(Coordinate a, Coordinate b, Coordinate c) {
        return (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
    }

    /** Рядом с углом Г (не дальше {@link #ALIGN_SUPPORT}) есть здание или дорога. */
    private boolean supported(Coordinate c) {
        Envelope env = new Envelope(c);
        env.expandBy(ALIGN_SUPPORT);
        org.locationtech.jts.geom.Point pt = field.gf.createPoint(c);
        for (Object o : field.blockIndex.query(env)) {
            ObstacleField.Block blk = (ObstacleField.Block) o;
            if (blk.restriction.rule == ru.lct.heatnet.rules.RestrictionRule.OKS
                    && blk.restriction.geometry.distance(pt) <= ALIGN_SUPPORT) {
                return true;
            }
        }
        for (Object o : field.zoneIndex.query(env)) {
            ObstacleField.Zone z = (ObstacleField.Zone) o;
            if (z.isAreal() && z.core.distance(pt) <= ALIGN_SUPPORT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Локальная ось застройки, град. [0, 90): преобладающее направление стен зданий и границ дорог в радиусе
     * {@link #AXIS_RADIUS} (по модулю 90°, вес — длина ребра, ближние сильнее). NaN — опорных стен мало или
     * направления разные.
     */
    double localAxisDeg(Coordinate p) {
        long key = ((long) Math.floor(p.x / AXIS_CELL) << 32) ^ ((long) Math.floor(p.y / AXIS_CELL) & 0xffffffffL);
        Double cached = axisCache.get(key);
        if (cached != null) {
            return cached;
        }
        Coordinate center = new Coordinate((Math.floor(p.x / AXIS_CELL) + 0.5) * AXIS_CELL,
                (Math.floor(p.y / AXIS_CELL) + 0.5) * AXIS_CELL);
        Envelope env = new Envelope(center);
        env.expandBy(AXIS_RADIUS);
        double[] hist = new double[90];
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Geometry> shapes = new ArrayList<>();
        for (Object o : field.blockIndex.query(env)) {
            ObstacleField.Block blk = (ObstacleField.Block) o;
            if (blk.restriction.rule == ru.lct.heatnet.rules.RestrictionRule.OKS && seen.add(blk.restriction)) {
                shapes.add(blk.restriction.geometry);
            }
        }
        for (Object o : field.zoneIndex.query(env)) {
            ObstacleField.Zone z = (ObstacleField.Zone) o;
            if (z.isAreal() && seen.add(z)) {
                shapes.add(z.core);
            }
        }
        double total = 0;
        // каждое кольцо (внешнее и дворы) каждой части отдельно: между кольцами и частями рёбер нет
        List<Coordinate[]> rings = new ArrayList<>();
        for (Geometry g : shapes) {
            for (int gi = 0; gi < g.getNumGeometries(); gi++) {
                Geometry part = g.getGeometryN(gi);
                if (part instanceof org.locationtech.jts.geom.Polygon) {
                    org.locationtech.jts.geom.Polygon poly = (org.locationtech.jts.geom.Polygon) part;
                    rings.add(poly.getExteriorRing().getCoordinates());
                    for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                        rings.add(poly.getInteriorRingN(h).getCoordinates());
                    }
                } else if (part instanceof LineString) {
                    rings.add(part.getCoordinates());
                }
            }
        }
        for (Coordinate[] cs : rings) {
            for (int i = 0; i + 1 < cs.length; i++) {
                double l = cs[i].distance(cs[i + 1]);
                if (l < 1e-6) {
                    continue;
                }
                double d = center.distance(new Coordinate((cs[i].x + cs[i + 1].x) / 2, (cs[i].y + cs[i + 1].y) / 2));
                if (d > AXIS_RADIUS) {
                    continue;
                }
                double w = l / (1 + d / 20.0);
                double ang = ((Math.toDegrees(Math.atan2(cs[i + 1].y - cs[i].y, cs[i + 1].x - cs[i].x)) % 90) + 90) % 90;
                hist[(int) Math.floor(ang) % 90] += w;
                total += w;
            }
        }
        double axis = Double.NaN;
        if (total >= 20) {
            int best = -1;
            double bestW = 0;
            for (int i = 0; i < 90; i++) {
                double w = 0;
                for (int k = -2; k <= 2; k++) {
                    w += hist[(i + k + 90) % 90];
                }
                if (w > bestW) {
                    bestW = w;
                    best = i;
                }
            }
            // ось должна собирать заметную долю стен: иначе направления в округе разные
            if (bestW >= 0.25 * total) {
                double sx = 0;
                double sw = 0;
                for (int k = -2; k <= 2; k++) {
                    int bin = (best + k + 90) % 90;
                    double angle = best + k + 0.5;
                    sx += hist[bin] * angle;
                    sw += hist[bin];
                }
                axis = ((sx / sw) % 90 + 90) % 90;
            }
        }
        axisCache.put(key, axis);
        return axis;
    }

    private Route reconstruct(State end, List<Coordinate> nodes, Goal goal) {
        List<Coordinate> pts = new ArrayList<>();
        List<List<SpecialSpan>> spans = new ArrayList<>();
        double length = 0;
        State s = end;
        while (s != null) {
            pts.add(nodes.get(s.cur));
            if (s.edge != null) {
                spans.add(s.edge.spans);
                length += s.edge.length;
            }
            if (s.parent == null && s.prev >= 0) {
                pts.add(nodes.get(s.prev));
            }
            s = s.parent;
        }
        Collections.reverse(pts);
        Collections.reverse(spans);
        return new Route(pts, spans, goal, length, end.g);
    }

    /** Поворот в b не больше 90°: скалярное произведение направлений неотрицательно (без арккосинуса). */
    static boolean turnOk(Coordinate a, Coordinate b, Coordinate c) {
        double x1 = b.x - a.x;
        double y1 = b.y - a.y;
        double x2 = c.x - b.x;
        double y2 = c.y - b.y;
        return x1 * x2 + y1 * y2 >= -1e-9 * Math.hypot(x1, y1) * Math.hypot(x2, y2);
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
        double cos = Math.max(-1, Math.min(1, (x1 * x2 + y1 * y2) / n));
        return Math.toDegrees(Math.acos(cos));
    }

    private static long key(int prev, int cur) {
        return ((long) (prev + 1) << 32) | (cur & 0xffffffffL);
    }

    private static final class State {
        final int prev;
        final int cur;
        final double g;
        final State parent;
        final SegmentChecker.Ok edge;
        double f;
        boolean closed;

        State(int prev, int cur, double g, State parent, SegmentChecker.Ok edge) {
            this.prev = prev;
            this.cur = cur;
            this.g = g;
            this.parent = parent;
            this.edge = edge;
        }
    }

    /** Для отладки: сколько рёбер видимости посчитано. */
    public int cachedVertices() {
        return visibility.size();
    }

    static <K, V> Map<K, V> identityMap() {
        return new IdentityHashMap<>();
    }
}
