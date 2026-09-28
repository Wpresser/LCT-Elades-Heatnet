package ru.lct.heatnet.solver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.Target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Построение вариантов режима 2D и их ранжирование по показателю S (§6).
 * Совместная сеть зависит от порядка обработки целей, поэтому для v1 и v2 пробуется несколько порядков
 * (в пределах бюджета времени) и берётся лучший по S.
 */
public final class SolveService {

    private static final Logger log = LoggerFactory.getLogger(SolveService.class);

    /**
     * Бюджет на перебор порядков для одного варианта, мс (переменная окружения HEATNET_ORDER_BUDGET_MS
     * или свойство heatnet.order-budget-ms). Первые {@link #MIN_ORDERS} порядка перебираются всегда:
     * иначе под нагрузкой (медленная машина) второй порядок пропускался и результат зависел от скорости
     * стенда (аудит: v1 S=13,73 вместо 12,93 на загруженном маке).
     */
    static final long ORDER_BUDGET_MS = budget();
    /**
     * Сколько порядков перебирается независимо от бюджета времени: все три, чтобы результат не зависел от скорости
     * стенда (§8 «Стабильность расчёта»). Бюджет действует только сверх этого числа.
     */
    static final int MIN_ORDERS = 3;

    /**
     * Выдавать ли базовый вариант «каждая цель отдельно» (свойство heatnet.separate-variant или переменная
     * HEATNET_SEPARATE_VARIANT = true). По умолчанию нет: v1 и v2 и так содержательно различаются (число
     * присоединений к существующей сети), а трассы «каждой отдельно» обходят друг друга и длиннее разумного —
     * этот вариант нужен только как базовая линия для сравнения.
     */
    static final boolean SEPARATE_VARIANT = Boolean.parseBoolean(firstNonEmpty(
            System.getProperty("heatnet.separate-variant"), System.getenv("HEATNET_SEPARATE_VARIANT"), "false"));

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static long budget() {
        String v = System.getProperty("heatnet.order-budget-ms");
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv("HEATNET_ORDER_BUDGET_MS");
        }
        try {
            return v == null || v.trim().isEmpty() ? 60_000 : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 60_000;
        }
    }
    /** Бюджет на базовый вариант «каждая отдельно», мс: на больших входах он самый медленный и наименее полезный. */
    static final long INDIVIDUAL_BUDGET_MS = 90_000;

    public List<VariantSummary> solve(InputModel m) {
        RouterPool pool = new RouterPool(m);
        Map<String, List<Target>> orders = orders(m);
        List<VariantSummary> out = new ArrayList<>();
        long start = System.nanoTime();
        out.add(bestTree(pool, m, "v1", orders, false));
        out.add(bestTree(pool, m, "v2", orders, true));
        long t0 = System.nanoTime();
        // на больших входах v3 медленнее совместных вариантов и всё равно не уложится в бюджет — не начинаем его
        boolean skip = !SEPARATE_VARIANT || (t0 - start) / 1_000_000 > 2 * ORDER_BUDGET_MS;
        if (skip && SEPARATE_VARIANT) {
            log.info("v3 не строится: совместные варианты заняли {} мс", (t0 - start) / 1_000_000);
        }
        Net individual = skip ? null
                : new IndividualSolver(pool).solve("v3", orders.values().iterator().next(), INDIVIDUAL_BUDGET_MS);
        if (individual != null) {
            out.add(VariantSummary.of(individual, m.targets));
        }
        if (SEPARATE_VARIANT) {
            log.info("v3 (каждая отдельно) за {} мс", (System.nanoTime() - t0) / 1_000_000);
        }
        out = withInvalidTargets(m, out);
        out.sort(Comparator.comparingDouble(v -> v.score));
        out = distinct(m, out);
        for (int i = 0; i < out.size(); i++) {
            out.get(i).rank = i + 1;
        }
        return out;
    }

    /**
     * Точки подключения без корректного flow_tph (InputModel#invalidTargets) не теряются: в каждом варианте они
     * среди неподключённых с причиной и штрафом (§2.5, §6). На ранжирование не влияют — штраф одинаков везде.
     */
    static List<VariantSummary> withInvalidTargets(InputModel m, List<VariantSummary> variants) {
        if (m.invalidTargets.isEmpty()) {
            return variants;
        }
        List<Target> all = new ArrayList<>(m.targets);
        all.addAll(m.invalidTargets.keySet());
        List<VariantSummary> out = new ArrayList<>();
        for (VariantSummary v : variants) {
            v.net.unconnected.putAll(m.invalidTargets);
            out.add(VariantSummary.of(v.net, all));
        }
        return out;
    }

    /** Доля трасс, которая должна совпасть (в пределах 1 м), чтобы варианты считались одинаковыми. */
    static final double SAME_SHARE = 0.95;

    /**
     * Убирает варианты, которые по сути повторяют лучший (§6: небольшое смещение трассы вариантом не считается):
     * если ≥ 95 % длины трасс каждого из двух вариантов лежит в пределах 1 м от трасс другого, остаётся лучший.
     */
    static List<VariantSummary> distinct(InputModel m, List<VariantSummary> sorted) {
        List<VariantSummary> kept = new ArrayList<>();
        List<org.locationtech.jts.geom.Geometry> nets = new ArrayList<>();
        for (VariantSummary v : sorted) {
            org.locationtech.jts.geom.Geometry g = geometry(m, v.net);
            boolean dup = false;
            for (int i = 0; i < kept.size() && !dup; i++) {
                dup = sameNetwork(g, nets.get(i))
                        && new java.util.HashSet<>(kept.get(i).unconnected).equals(new java.util.HashSet<>(v.unconnected));
            }
            if (dup) {
                log.info("{}: совпадает с лучшим вариантом, не выдаётся", v.net.variantId);
                continue;
            }
            kept.add(v);
            nets.add(g);
        }
        return kept;
    }

    private static org.locationtech.jts.geom.Geometry geometry(InputModel m, Net net) {
        List<org.locationtech.jts.geom.Geometry> lines = new ArrayList<>();
        for (Net.Line l : net.lines) {
            lines.add(m.gf.createLineString(l.coords.toArray(new org.locationtech.jts.geom.Coordinate[0])));
        }
        return m.gf.buildGeometry(lines);
    }

    private static boolean sameNetwork(org.locationtech.jts.geom.Geometry a, org.locationtech.jts.geom.Geometry b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty();
        }
        return share(a, b) >= SAME_SHARE && share(b, a) >= SAME_SHARE;
    }

    private static double share(org.locationtech.jts.geom.Geometry lines, org.locationtech.jts.geom.Geometry other) {
        double len = lines.getLength();
        return len == 0 ? 1 : lines.intersection(other.buffer(1.0)).getLength() / len;
    }

    private VariantSummary bestTree(RouterPool pool, InputModel m, String vid, Map<String, List<Target>> orders, boolean single) {
        long t0 = System.nanoTime();
        VariantSummary best = null;
        String bestOrder = null;
        long last = 0;
        int tried = 0;
        for (Map.Entry<String, List<Target>> e : orders.entrySet()) {
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            if (best != null && tried >= MIN_ORDERS && elapsed + last > ORDER_BUDGET_MS) {
                log.info("{}: порядок «{}» пропущен — не укладывается в бюджет {} мс", vid, e.getKey(), ORDER_BUDGET_MS);
                best.net.searchTruncated = true;
                break;
            }
            long s0 = System.nanoTime();
            tried++;
            Net net = new TreeSolver(pool).solve(vid, e.getValue(), single);
            last = (System.nanoTime() - s0) / 1_000_000;
            VariantSummary s = VariantSummary.of(net, m.targets);
            log.info("{} порядок «{}»: подключено {}, S={}", vid, e.getKey(), m.targets.size() - s.unconnected.size(), s.score);
            if (best == null || s.unconnected.size() < best.unconnected.size()
                    || (s.unconnected.size() == best.unconnected.size() && s.score < best.score)) {
                best = s;
                bestOrder = e.getKey();
            }
        }
        best.net.description += "; порядок целей: " + bestOrder;
        log.info("{}: лучший порядок «{}» за {} мс", vid, bestOrder, (System.nanoTime() - t0) / 1_000_000);
        return best;
    }

    /** Порядки обработки целей. */
    static Map<String, List<Target>> orders(InputModel m) {
        Map<String, List<Target>> out = new LinkedHashMap<>();
        List<Target> byDistance = IndividualSolver.byDistance(m);
        out.put("ближе к сети — раньше", byDistance);
        List<Target> byFlow = new ArrayList<>(m.targets);
        byFlow.sort(Comparator.comparingDouble((Target t) -> -t.flowTph).thenComparing(t -> t.id.toString()));
        out.put("больший расход — раньше", byFlow);
        out.put("ближайшая к уже подключённым", prim(m, byDistance));
        return out;
    }

    /**
     * Жадный порядок «как у Прима»: первой — ближайшая к сети цель, далее — цель, ближайшая к сети
     * или к любой уже выбранной цели (по прямой).
     */
    static List<Target> prim(InputModel m, List<Target> byDistance) {
        TieInGoals probe = new TieInGoals(m, 50, new NetworkState(m.gf, new Net("probe")));
        List<Target> rest = new ArrayList<>(byDistance);
        List<Target> out = new ArrayList<>();
        Map<Target, Double> best = new LinkedHashMap<>();
        for (Target t : rest) {
            best.put(t, probe.lowerBoundDistance(t.point.getCoordinate()));
        }
        while (!rest.isEmpty()) {
            Target next = null;
            for (Target t : rest) {
                if (next == null || best.get(t) < best.get(next)) {
                    next = t;
                }
            }
            rest.remove(next);
            out.add(next);
            for (Target t : rest) {
                best.put(t, Math.min(best.get(t), t.point.distance(next.point)));
            }
        }
        return out;
    }
}
