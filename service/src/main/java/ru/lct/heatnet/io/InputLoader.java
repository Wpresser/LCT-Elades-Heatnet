package ru.lct.heatnet.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;
import org.locationtech.jts.operation.valid.IsValidOp;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.Diagnostics;
import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.ExistingChamber;
import ru.lct.heatnet.model.InputObjects.ExistingPipe;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.model.InputObjects.Source;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.rules.RestrictionRule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Загрузка входа в два прохода по файлу:
 * 1) источники, сеть, камеры, цели — в память (с лимитом);
 * 2) ограничения — только если их охват пересекает окна поиска вокруг целей.
 */
public final class InputLoader {

    /** Запас к окну при отборе ограничений: отступы, габариты и зоны спецпроходов, м. */
    public static final double WINDOW_SELECT_MARGIN = 30.0;
    /** Минимальный радиус окна поиска вокруг цели, м. */
    public static final double WINDOW_MIN_RADIUS = 300.0;
    /** Окно = max(мин. радиус, k · расстояние до ближайшей трубы + запас). */
    public static final double WINDOW_DIST_FACTOR = 1.5;
    public static final double WINDOW_EXTRA = 150.0;

    private static final Set<String> WGS84_CRS_NAMES = new HashSet<>(Arrays.asList(
            "urn:ogc:def:crs:OGC:1.3:CRS84", "urn:ogc:def:crs:OGC::CRS84", "CRS84",
            "EPSG:4326", "urn:ogc:def:crs:EPSG::4326", "urn:ogc:def:crs:EPSG:4326"));

    private final ObjectMapper mapper;
    private final AppProperties.Limits limits;
    /** Формат исходной редакции приложения: расход oks_future по id и ожидающие его точки без flow_tph. */
    private final java.util.Map<String, Double> legacyFlow = new java.util.HashMap<>();
    private final java.util.Map<Target, String> legacyPending = new java.util.LinkedHashMap<>();
    /** Сколько раз встретился id среди узловых объектов (точки подключения, камеры, источники). */
    private final java.util.Map<String, Integer> nodeIds = new java.util.HashMap<>();

    public InputLoader(ObjectMapper mapper, AppProperties.Limits limits) {
        this.mapper = mapper;
        this.limits = limits;
    }

    public InputModel load(Path file) throws IOException {
        return load(file, 1.0);
    }

    /** @param windowScale множитель радиуса окон (для повторной загрузки с расширением). */
    public InputModel load(Path file, double windowScale) throws IOException {
        legacyFlow.clear();
        legacyPending.clear();
        nodeIds.clear();
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);
        Projection projection = new Projection();
        GeometryParser geo = new GeometryParser(projection, gf);
        InputModel m = new InputModel(gf);
        Diagnostics d = m.diagnostics;

        long total = GeoJsonFeatureReader.read(file, mapper, new GeoJsonFeatureReader.Handler() {
            @Override
            public void onCrs(JsonNode crs) {
                String name = crs.path("properties").path("name").asText("");
                String n = name.trim().toUpperCase(java.util.Locale.ROOT);
                if (name.isEmpty() || WGS84_CRS_NAMES.contains(name) || n.contains("4326") || n.contains("CRS84")) {
                    return;
                }
                if (n.matches(".*EPSG:+[0-9.:]*[0-9]+.*")) {
                    // явно другая СК (например, EPSG:3857): координаты в метрах, читать их как градусы нельзя
                    throw new InputFormatException("Ожидалась СК WGS 84 (EPSG:4326), в файле: " + name);
                }
                // иное написание имени СК без кода EPSG: считаем координаты WGS 84 (§1); объекты вне допустимых
                // широт и долгот отбрасываются при разборе с замечанием
                d.warn("СК «" + name + "» не распознана как WGS 84 (EPSG:4326); координаты прочитаны как WGS 84");
            }

            @Override
            public void onFeature(JsonNode f, long index) {
                readCore(f, index, m, geo);
                if (m.sources.size() + m.pipes.size() + m.chambers.size() + m.targets.size() > limits.getMaxCoreObjects()) {
                    throw new InputFormatException("Слишком много объектов сети и целей (> "
                            + limits.getMaxCoreObjects() + "): превышен поддерживаемый объём, см. документацию");
                }
            }
        });
        d.set("features.total", total);
        assignMissingPipeDiameters(m);
        resolveLegacyFlows(m);

        for (ExistingPipe p : m.pipes) {
            m.pipeIndex.insert(p.line.getEnvelopeInternal(), p);
        }
        for (ExistingChamber c : m.chambers) {
            m.chamberIndex.insert(c.point.getEnvelopeInternal(), c);
        }
        m.pipeIndex.build();
        m.chamberIndex.build();

        buildWindows(m, windowScale);
        STRtree windowIndex = new STRtree();
        for (Envelope w : m.windows) {
            windowIndex.insert(w, w);
        }
        windowIndex.build();

        GeoJsonFeatureReader.read(file, mapper, (f, index) -> {
            String t = objectType(f.path("properties"));
            if ("restriction".equals(t) || "oks_future".equals(t) || "oks_existing".equals(t)) {
                readRestriction(f, index, m, geo, windowIndex);
                if (m.restrictions.size() > limits.getMaxRestrictions()) {
                    throw new InputFormatException("Слишком много ограничений в окнах поиска (> "
                            + limits.getMaxRestrictions() + "): превышен поддерживаемый объём");
                }
            }
        });
        for (Restriction r : m.restrictions) {
            m.restrictionIndex.insert(r.geometry.getEnvelopeInternal(), r);
        }
        m.restrictionIndex.build();

        computeChamberAdjacency(m);
        snapFacadeTargets(m);
        findOwnOks(m);
        checkNetwork(m);
        d.set("objects.source", m.sources.size());
        d.set("objects.heat_network", m.pipes.size());
        d.set("objects.heat_chamber", m.chambers.size());
        d.set("objects.oks_connection_point", m.targets.size());
        d.set("objects.restriction_loaded", m.restrictions.size());
        return m;
    }

    private void readCore(JsonNode f, long index, InputModel m, GeometryParser geo) {
        JsonNode props = f.path("properties");
        String type = objectType(props);
        Diagnostics d = m.diagnostics;
        if (type.equals("restriction")) {
            d.count("input.restriction");
            return;
        }
        if (type.equals("oks_future") || type.equals("oks_existing")) {
            // исходная редакция приложения (10.09): здания читаются во втором проходе как ограничения oks
            d.count("input.legacy_oks");
            if (d.get("input.legacy_oks") == 1) {
                d.warn("во входе объекты oks_future/oks_existing (исходная редакция приложения): здания учтены как "
                        + "ограничения oks, расход точки подключения без flow_tph берётся из связанного oks_future (oks_id)");
            }
            FeatureId fid = FeatureId.fromJson(props.get("id"));
            Double fl = type.equals("oks_future") ? number(props.get("flow_tph"), d, "oks_future " + fid + ": flow_tph") : null;
            if (fid != null && fl != null && fl >= 0 && !fl.isInfinite()) {
                legacyFlow.put(fid.toString(), fl);
            }
            return;
        }
        FeatureId id = FeatureId.fromJson(props.get("id"));
        String where = "объект №" + index + " (" + (type.isEmpty() ? "без object_type" : type)
                + (id == null ? "" : ", id=" + id) + ")";
        if (id == null) {
            if (!type.isEmpty()) {
                skip(d, where + ": нет id");
            }
            return;
        }
        if (type.equals("oks_connection_point") || type.equals("heat_chamber") || type.equals("source")) {
            int seen = nodeIds.merge(id.toString(), 1, Integer::sum);
            if (seen == 2) {
                d.count("input.duplicate_node_id");
                d.warn(where + ": id уже встречался у другой точки подключения, камеры или источника — ссылки на узел в выгрузке неоднозначны");
            }
        }
        try {
            JsonNode g = f.get("geometry");
            switch (type) {
                case "source": {
                    Geometry geom = geo.parse(g);
                    requirePoint(geom);
                    m.sources.add(new Source(id, GeometryParser.lonLatOfPoint(g), (Point) geom));
                    break;
                }
                case "heat_network": {
                    Double dnValue = number(props.get("diameter"), d, where + ": diameter");
                    // без корректного diameter труба не теряется (иначе её можно было бы пересечь): ДУ = 0 здесь,
                    // после чтения всех объектов принимается наибольший ДУ сети в файле (консервативно)
                    int dn = dnValue == null || !(dnValue > 0) ? 0 : (int) Math.round(dnValue);
                    Geometry geom = geo.parse(g);
                    if (geom instanceof LineString) {
                        m.pipes.add(new ExistingPipe(id, dn, (LineString) geom, 0));
                    } else if (geom instanceof MultiLineString) {
                        d.warn(where + ": MultiLineString разбит на " + geom.getNumGeometries() + " частей");
                        for (int i = 0; i < geom.getNumGeometries(); i++) {
                            m.pipes.add(new ExistingPipe(id, dn, (LineString) geom.getGeometryN(i), i));
                        }
                    } else {
                        skip(d, where + ": ожидалась LineString");
                    }
                    break;
                }
                case "heat_chamber": {
                    Geometry geom = geo.parse(g);
                    requirePoint(geom);
                    m.chambers.add(new ExistingChamber(id, GeometryParser.lonLatOfPoint(g), (Point) geom));
                    break;
                }
                case "oks_connection_point": {
                    Double flow = number(props.get("flow_tph"), d, where + ": flow_tph");
                    Geometry geom;
                    try {
                        geom = geo.parse(g);
                        requirePoint(geom);
                    } catch (InputFormatException e) {
                        // точка не теряется: в выгрузке она среди неподключённых с причиной
                        d.count("input.invalid_target_geometry");
                        d.warn(where + ": " + e.getMessage() + " — маршрут не строится, точка в unconnected_oks_ids");
                        double fl = flow != null && flow >= 0 && !flow.isInfinite() ? flow : 0.0;
                        Coordinate zero = new Coordinate(0, 0);
                        m.invalidTargets.put(new Target(id, fl, zero, m.gf.createPoint(zero)),
                                "нет корректной геометрии точки подключения во входных данных");
                        return;
                    }
                    // нулевой расход допустим (цель всё равно подключается, ДУ минимальный); NaN и отрицательный — нет
                    if (flow == null || !(flow >= 0) || flow.isInfinite()) {
                        // цель не теряется: в выгрузке она среди неподключённых с причиной (штраф — с расходом 0)
                        d.count("input.invalid_flow");
                        d.warn(where + ": нет корректного flow_tph — маршрут не строится, точка в unconnected_oks_ids");
                        Target t = new Target(id, 0.0, GeometryParser.lonLatOfPoint(g), (Point) geom);
                        m.invalidTargets.put(t, "нет корректного flow_tph во входных данных (штраф посчитан с расходом 0)");
                        JsonNode oksId = props.get("oks_id");
                        if (oksId != null && !oksId.isNull() && !oksId.asText().isEmpty()) {
                            legacyPending.put(t, oksId.asText());
                        }
                        return;
                    }
                    m.targets.add(new Target(id, flow, GeometryParser.lonLatOfPoint(g), (Point) geom));
                    break;
                }
                case "":
                    skip(d, where + ": нет object_type");
                    break;
                default:
                    d.count("input.unknown_object_type");
                    d.warn(where + ": неизвестный object_type, объект пропущен");
            }
        } catch (InputFormatException e) {
            skip(d, where + ": " + e.getMessage());
        }
    }

    /**
     * Трубы без корректного diameter получают наибольший ДУ существующей сети из того же файла (DN400, если других
     * нет): габарит и стоимость камеры на такой трубе тогда не занижаются.
     */
    private static void assignMissingPipeDiameters(InputModel m) {
        int max = 0;
        for (ExistingPipe p : m.pipes) {
            max = Math.max(max, p.diameter);
        }
        int assumed = max > 0 ? max : 400;
        for (int i = 0; i < m.pipes.size(); i++) {
            ExistingPipe p = m.pipes.get(i);
            if (p.diameter <= 0) {
                m.pipes.set(i, new ExistingPipe(p.id, assumed, p.line, p.part));
                if (p.part == 0) {
                    m.diagnostics.count("input.pipe_without_diameter");
                    m.diagnostics.warn("участок существующей сети " + p.id + ": нет корректного diameter — принят ДУ "
                            + assumed + " (наибольший в файле)");
                }
            }
        }
    }

    /** Точки без flow_tph в формате исходной редакции получают расход своего oks_future (по oks_id). */
    private void resolveLegacyFlows(InputModel m) {
        for (java.util.Map.Entry<Target, String> e : legacyPending.entrySet()) {
            Double fl = legacyFlow.get(e.getValue());
            if (fl == null) {
                continue;
            }
            Target t = e.getKey();
            m.invalidTargets.remove(t);
            m.targets.add(new Target(t.id, fl, t.lonLat, t.point));
            m.diagnostics.warn("точка подключения " + t.id + ": flow_tph взят из oks_future " + e.getValue() + " (" + fl + " т/ч)");
        }
    }

    /** object_type без учёта регистра и пробелов по краям. */
    static String objectType(JsonNode props) {
        return props.path("object_type").asText("").trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Число из JSON-числа или из строки с числом («12.5», «12,5»); иначе null. */
    static Double number(JsonNode n, Diagnostics d, String where) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isTextual()) {
            try {
                double v = Double.parseDouble(n.asText().trim().replace(',', '.'));
                d.warn(where + " задан строкой «" + n.asText() + "», прочитан как число " + v);
                return v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static void requirePoint(Geometry g) {
        if (!(g instanceof Point)) {
            throw new InputFormatException("ожидалась геометрия Point");
        }
    }

    private static void skip(Diagnostics d, String msg) {
        d.count("input.skipped");
        d.warn(msg);
    }

    private void readRestriction(JsonNode f, long index, InputModel m, GeometryParser geo, STRtree windowIndex) {
        JsonNode props = f.path("properties");
        Diagnostics d = m.diagnostics;
        FeatureId id = FeatureId.fromJson(props.get("id"));
        String otype = objectType(props);
        String rtype = otype.equals("oks_future") || otype.equals("oks_existing") ? "oks"
                : props.path("restriction_type").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        String where = "ограничение №" + index + (id == null ? "" : " (id=" + id + ")");
        if (rtype.isEmpty()) {
            skip(d, where + ": нет restriction_type");
            return;
        }
        if (id == null) {
            // id ограничения в выгрузку не попадает: без него объект остаётся препятствием с внутренним id
            id = FeatureId.of("__restriction#" + index);
            d.warn(where + ": нет id — учтено как препятствие с внутренним id");
        }
        d.count("restriction_type." + rtype);
        RestrictionRule rule = RestrictionRule.byCode(rtype).orElse(null);
        if (rule == null) {
            d.count("restriction.unsupported");
            if (d.get("restriction_type." + rtype) == 1) {
                d.warn(rtype.equals("heat_network")
                        ? "restriction_type=heat_network: существующая сеть задаётся объектами heat_network с diameter; "
                          + "ограничение этого типа без ДУ не учтено"
                        : "restriction_type=" + rtype + " не входит в таблицу 2 — такие ограничения не учитываются");
            }
            return;
        }
        Geometry geom;
        try {
            geom = geo.parse(f.get("geometry"));
        } catch (InputFormatException e) {
            skip(d, where + ": " + e.getMessage());
            return;
        }
        Envelope env = new Envelope(geom.getEnvelopeInternal());
        env.expandBy(WINDOW_SELECT_MARGIN);
        if (windowIndex.query(env).isEmpty()) {
            d.count("restriction.outside_windows");
            return;
        }
        if (geom.getDimension() == 2 && !geom.isValid()) {
            String reason = new IsValidOp(geom).getValidationError().getMessage();
            geom = geom.buffer(0);
            d.count("restriction.fixed_invalid");
            d.warn(where + ": невалидный полигон (" + reason + "), исправлен buffer(0)");
        }
        if (geom.isEmpty()) {
            skip(d, where + ": пустая геометрия");
            return;
        }
        // Части MultiPolygon/MultiLineString — отдельные объекты: «свой» ОКС для финального подхода — только
        // часть с целью (DECISIONS №26), а каждое пересечение части — отдельный спецпроход (§4).
        if (geom.getNumGeometries() > 1) {
            d.add("restriction.parts_split", geom.getNumGeometries());
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                Geometry part = geom.getGeometryN(i);
                if (!part.isEmpty()) {
                    m.restrictions.add(new Restriction(id, rtype, rule, part));
                }
            }
        } else {
            m.restrictions.add(new Restriction(id, rtype, rule, geom.getGeometryN(0)));
        }
    }

    private void buildWindows(InputModel m, double scale) {
        List<Polygon> polys = new ArrayList<>();
        for (Target t : m.targets) {
            double dist = nearestPipeDistance(m, t.point);
            double r = Math.max(WINDOW_MIN_RADIUS, WINDOW_DIST_FACTOR * dist + WINDOW_EXTRA) * scale;
            Envelope w = new Envelope(t.point.getCoordinate());
            w.expandBy(r);
            m.windows.add(w);
            polys.add((Polygon) m.gf.toGeometry(w));
        }
        m.roi = polys.isEmpty() ? m.gf.createPolygon() : CascadedPolygonUnion.union(polys);
    }

    private static double nearestPipeDistance(InputModel m, Point p) {
        if (m.pipes.isEmpty()) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        double r = 100;
        // расширяем поиск, пока не найдём трубу
        while (best == Double.MAX_VALUE) {
            Envelope env = new Envelope(p.getCoordinate());
            env.expandBy(r);
            for (ExistingPipe pipe : m.pipesNear(env)) {
                best = Math.min(best, pipe.line.distance(p));
            }
            r *= 2;
            if (r > 1e7) {
                break;
            }
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    /** Примыкания: конец существующей линии в камере = 1, линия, проходящая через камеру, = 2 (§2.1). */
    private static void computeChamberAdjacency(InputModel m) {
        for (ExistingChamber c : m.chambers) {
            Envelope env = new Envelope(c.point.getCoordinate());
            env.expandBy(InputModel.TOPO_TOL);
            int adj = 0;
            for (ExistingPipe p : m.pipesNear(env)) {
                Coordinate a = p.line.getCoordinateN(0);
                Coordinate b = p.line.getCoordinateN(p.line.getNumPoints() - 1);
                int ends = (a.distance(c.point.getCoordinate()) <= InputModel.TOPO_TOL ? 1 : 0)
                        + (b.distance(c.point.getCoordinate()) <= InputModel.TOPO_TOL ? 1 : 0);
                if (ends > 0) {
                    adj += ends;
                } else if (p.line.distance(c.point) <= InputModel.TOPO_TOL) {
                    adj += 2;
                }
            }
            c.existingAdjacency = adj;
            if (adj == 0) {
                m.diagnostics.warn("камера " + c.id + " не лежит на существующей сети (допуск "
                        + InputModel.TOPO_TOL + " м)");
            }
            if (adj > 4) {
                m.diagnostics.warn("к камере " + c.id + " уже примыкает " + adj + " участков (> 4)");
            }
        }
    }

    /**
     * «Свой» ОКС цели — весь объект (все части с тем же id), в части которого лежит цель (DECISIONS №26):
     * финальный участок освобождён от отступа до всех его частей, но сквозь них проходить нельзя.
     */
    /** Точка на контуре ОКС или снаружи не дальше этого расстояния считается точкой на фасаде своего здания, м. */
    static final double FACADE_SNAP = 0.5;
    /** На сколько точка на фасаде сдвигается внутрь здания для расчёта, м (в выгрузке — исходные координаты). */
    static final double FACADE_INSET = 0.05;

    /**
     * ТЗ §1.1: «достаточно определить точку подключения на границе ОКС». Точка на контуре здания (или снаружи
     * не дальше {@link #FACADE_SNAP}, погрешность оцифровки) для расчёта сдвигается на {@link #FACADE_INSET} внутрь
     * своего здания по нормали к ближайшей стене: тогда финальный участок идёт от этой стены наружу по обычным
     * правилам §2.2. В выгрузке точка остаётся с исходными координатами; сдвиг — в замечаниях к входу.
     */
    private static void snapFacadeTargets(InputModel m) {
        for (int i = 0; i < m.targets.size(); i++) {
            Target t = m.targets.get(i);
            Coordinate tc = t.point.getCoordinate();
            Envelope env = new Envelope(tc);
            env.expandBy(FACADE_SNAP + 1);
            Polygon bestPart = null;
            double bestD = Double.MAX_VALUE;
            boolean deepInside = false;
            for (Restriction r : m.restrictionsNear(env)) {
                if (r.rule != RestrictionRule.OKS || !r.isAreal()) {
                    continue;
                }
                for (int k = 0; k < r.geometry.getNumGeometries(); k++) {
                    Geometry g = r.geometry.getGeometryN(k);
                    if (!(g instanceof Polygon)) {
                        continue;
                    }
                    double db = g.getBoundary().distance(t.point);
                    boolean inside = g.covers(t.point);
                    if (inside && db >= FACADE_INSET) {
                        deepInside = true;
                    } else if ((inside || db <= FACADE_SNAP) && db < bestD) {
                        bestD = db;
                        bestPart = (Polygon) g;
                    }
                }
            }
            if (deepInside || bestPart == null) {
                continue;
            }
            Coordinate b = org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(bestPart.getBoundary(), t.point)[0];
            double dx = tc.x - b.x;
            double dy = tc.y - b.y;
            double len = Math.hypot(dx, dy);
            double nx;
            double ny;
            if (len > 1e-9) {
                double sign = bestPart.covers(t.point) ? 1 : -1; // внутрь здания
                nx = sign * dx / len;
                ny = sign * dy / len;
            } else {
                double[] n = inwardNormal(bestPart, b);
                if (n == null) {
                    continue;
                }
                nx = n[0];
                ny = n[1];
            }
            Coordinate moved = new Coordinate(b.x + nx * FACADE_INSET, b.y + ny * FACADE_INSET);
            Point p = m.gf.createPoint(moved);
            if (!bestPart.contains(p)) {
                continue;
            }
            m.targets.set(i, new Target(t.id, t.flowTph, t.lonLat, p, t.point));
            m.diagnostics.count("targets.on_facade");
            m.diagnostics.warn("точка подключения " + t.id + " на контуре своего ОКС ("
                    + (bestPart.covers(t.point) ? "внутри" : "снаружи") + " на " + String.format(java.util.Locale.ROOT, "%.2f", len)
                    + " м): для расчёта сдвинута на 5 см внутрь, в выгрузке — исходные координаты");
        }
    }

    /** Нормаль к стене в точке b, направленная внутрь полигона (по отрезку контура, ближайшему к b). */
    private static double[] inwardNormal(Polygon part, Coordinate b) {
        org.locationtech.jts.geom.LineSegment best = null;
        double bestD = Double.MAX_VALUE;
        java.util.List<org.locationtech.jts.geom.LineString> rings = new ArrayList<>();
        rings.add(part.getExteriorRing());
        for (int k = 0; k < part.getNumInteriorRing(); k++) {
            rings.add(part.getInteriorRingN(k));
        }
        for (org.locationtech.jts.geom.LineString ring : rings) {
            Coordinate[] c = ring.getCoordinates();
            for (int k = 0; k + 1 < c.length; k++) {
                org.locationtech.jts.geom.LineSegment seg = new org.locationtech.jts.geom.LineSegment(c[k], c[k + 1]);
                double d = seg.distance(b);
                if (d < bestD && seg.getLength() > 1e-9) {
                    bestD = d;
                    best = seg;
                }
            }
        }
        if (best == null) {
            return null;
        }
        double sx = (best.p1.x - best.p0.x) / best.getLength();
        double sy = (best.p1.y - best.p0.y) / best.getLength();
        for (double sign : new double[]{1, -1}) {
            double nx = -sy * sign;
            double ny = sx * sign;
            if (part.contains(part.getFactory().createPoint(new Coordinate(b.x + nx * FACADE_INSET, b.y + ny * FACADE_INSET)))) {
                return new double[]{nx, ny};
            }
        }
        return null;
    }

    private static void findOwnOks(InputModel m) {
        java.util.Map<FeatureId, List<Restriction>> oksParts = new java.util.HashMap<>();
        for (Restriction r : m.restrictions) {
            if (r.rule == RestrictionRule.OKS) {
                oksParts.computeIfAbsent(r.id, k -> new ArrayList<>()).add(r);
            }
        }
        int inside = 0;
        for (Target t : m.targets) {
            Set<FeatureId> ids = new java.util.LinkedHashSet<>();
            for (Restriction r : m.restrictionsNear(t.point.getEnvelopeInternal())) {
                if (r.rule == RestrictionRule.OKS && r.isAreal() && r.geometry.covers(t.point)) {
                    ids.add(r.id);
                }
            }
            List<Restriction> own = new ArrayList<>();
            ids.forEach(id -> own.addAll(oksParts.get(id)));
            if (!own.isEmpty()) {
                m.ownOks.put(t, own);
                inside++;
            }
            for (Restriction r : m.restrictionsNear(t.point.getEnvelopeInternal())) {
                if (r.rule != RestrictionRule.OKS && r.rule.kind == RestrictionRule.Kind.FORBIDDEN
                        && r.isAreal() && r.geometry.covers(t.point)) {
                    m.diagnostics.warn("точка подключения " + t.id + " лежит внутри запретного ограничения "
                            + r.type + " id=" + r.id);
                }
            }
        }
        m.diagnostics.set("targets.inside_own_oks", inside);
    }

    /** Диагностика: связность существующей сети по концам линий и положение источника. */
    private static void checkNetwork(InputModel m) {
        int n = m.pipes.size();
        java.util.Map<ExistingPipe, Integer> index = new java.util.IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(m.pipes.get(i), i);
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            LineString li = m.pipes.get(i).line;
            for (Coordinate end : new Coordinate[]{li.getCoordinateN(0), li.getCoordinateN(li.getNumPoints() - 1)}) {
                Envelope env = new Envelope(end);
                env.expandBy(InputModel.TOPO_TOL);
                Point pt = m.gf.createPoint(end);
                for (ExistingPipe other : m.pipesNear(env)) {
                    int j = index.get(other);
                    if (j != i && other.line.distance(pt) <= InputModel.TOPO_TOL) {
                        union(parent, i, j);
                    }
                }
            }
        }
        Set<Integer> roots = new HashSet<>();
        for (int i = 0; i < n; i++) {
            roots.add(find(parent, i));
        }
        m.diagnostics.set("network.components", roots.size());
        if (roots.size() > 1) {
            m.diagnostics.warn("существующая сеть состоит из " + roots.size() + " несвязанных частей");
        }
        for (Source s : m.sources) {
            Envelope env = new Envelope(s.point.getCoordinate());
            env.expandBy(InputModel.TOPO_TOL);
            boolean onNet = m.pipesNear(env).stream().anyMatch(p -> p.line.distance(s.point) <= InputModel.TOPO_TOL);
            if (!onNet) {
                m.diagnostics.warn("источник " + s.id + " не лежит на существующей сети");
            }
        }
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }
}
