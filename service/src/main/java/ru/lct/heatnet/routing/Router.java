package ru.lct.heatnet.routing;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
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
        if (!allowRelaxed) {
            return strictResult != null ? strictResult : new Result(null, "строгий финальный подход недоступен (§2.2)", 0);
        }
        // нестрогий подход — только если строгий невозможен и без наших построенных линий
        DynamicObstacles none = new DynamicObstacles(field.gf);
        Router probe = strictProbe != null ? strictProbe : this;
        List<Coordinate> staticStrict = probe.portals(t, ownOks, none, true);
        if (!staticStrict.isEmpty() && probe.search(target, ownOks, staticStrict, false, none, goals, 0).route != null) {
            return new Result(null, "строгий финальный подход перекрыт уже построенными линиями (§2.2)", 0);
        }
        // строгий выход закрыт или из него нет трассы — пробуем несколько нестрогих выходов сразу
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
                Route r = smooth(reconstruct(s, nodes, goalNodes.get(s.cur)), dyn, !portals.isEmpty(), startExemptRadius);
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
                if (p != null && !turnOk(p, c, g.point) || !g.branchTurnOk(c)) {
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
                if (p != null && !turnOk(p, c, n)) {
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
                State ns = new State(s.cur, e.to, g, s, e.ok);
                ns.f = g + heuristic(n, goals);
                states.put(k, ns);
                open.add(ns);
            }
        }
        return new Result(null, "допустимая трасса до существующей сети не найдена (поиск в окне вокруг цели; окно "
                + "расширялось)", expansions);
    }

    /**
     * Трасса от точки (камеры, без финального подхода) до цели поиска; у старта ослаблена проверка построенных линий,
     * т. к. к этой камере уже примыкают другие участки.
     */
    public Result routeFromNode(Target pseudo, DynamicObstacles dyn, GoalProvider goals, double exemptRadius) {
        return search(pseudo, Collections.emptyList(), Collections.emptyList(), false, dyn, goals, exemptRadius);
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
        List<Coordinate> out = portalsImpl(t, own, dyn, strict, 1);
        return out.isEmpty() ? null : new Coordinate[]{out.get(0)};
    }

    private List<Coordinate> relaxedPortals(Coordinate t, List<Restriction> own, DynamicObstacles dyn) {
        return portalsImpl(t, own, dyn, false, MAX_RELAXED_PORTALS);
    }

    /** Нестрогие выходы берутся по возрастанию расстояния, направления различаются хотя бы на 10°. */
    private List<Coordinate> portalsImpl(Coordinate t, List<Restriction> own, DynamicObstacles dyn, boolean strict, int limit) {
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
            Coordinate[] a = ray(t, pb, ignored, ownUnion, dyn);
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
            if (c.distance(t) > nearest + 60 || ++tries > 1500 || found.size() >= limit) {
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
            Coordinate[] a = ray(t, c, ignored, ownUnion, dyn);
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

    /** Точка на луче «цель → точка контура» за пределами зон запрета, до которой отрезок от цели допустим. */
    private Coordinate[] ray(Coordinate t, Coordinate pb, Set<Restriction> ignored, Geometry ownUnion, DynamicObstacles dyn) {
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
        for (double s = APPROACH_STEP; s <= MAX_APPROACH; s += APPROACH_STEP) {
            Coordinate a = new Coordinate(pb.x + dx * s, pb.y + dy * s);
            if (ownPrepared.intersects(field.segment(out, a))) {
                if (debug) {
                    log.info("  луч: на {} м снова входит в свой ОКС", s);
                }
                return null; // снаружи луч снова заходит в свой ОКС: финальный участок пересекал бы его дважды
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
    private Route smooth(Route r, DynamicObstacles dyn, boolean hasPortal, double startExemptRadius) {
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
        Route s = new Route(out, spans, r.goal, length, cost);
        return s;
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
