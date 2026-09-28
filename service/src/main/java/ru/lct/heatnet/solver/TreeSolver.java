package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.routing.Router;
import ru.lct.heatnet.rules.DiameterTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Совместное подключение: цели по очереди присоединяются к существующей сети или к уже построенной новой
 * (разветвление в новой камере). Выбор между ними — по цене в показателе S, включая камеру и удорожание
 * общего участка. После каждого присоединения расходы и ДУ пересчитываются по дереву; если ДУ какого-то участка
 * превысил габарит, с которым трасса проверялась, присоединение откатывается.
 * Трассы прокладываются с габаритом ДУ суммарного расхода, поэтому рост ДУ на общих участках их не ломает.
 */
public final class TreeSolver {

    private static final Logger log = LoggerFactory.getLogger(TreeSolver.class);

    enum Mode { BOTH, TIE_IN_ONLY, BRANCH_ONLY }

    private final RouterPool pool;
    private final InputModel m;

    public TreeSolver(RouterPool pool) {
        this.pool = pool;
        this.m = pool.model();
    }

    /**
     * @param singleEntry стараться обойтись одним присоединением к существующей сети: после первой цели
     *                    остальные сначала пробуют только ветки новой сети
     */
    public Net solve(String variantId, List<Target> order, boolean singleEntry) {
        Net net = new Net(variantId);
        net.description = singleEntry
                ? "совместное подключение с минимумом присоединений к существующей сети"
                : "совместное подключение: цели присоединяются к существующей или уже построенной новой сети";
        double total = 0;
        for (Target t : order) {
            total += t.flowTph;
        }
        int wide = DiameterTable.minByFlow(total).map(r -> r.dn).orElse(DiameterTable.max().dn);
        for (Target t : order) {
            String fail;
            if (singleEntry && !net.lines.isEmpty()) {
                fail = connect(net, t, wide, Mode.BRANCH_ONLY);
                if (fail != null) {
                    fail = connect(net, t, wide, Mode.BOTH);
                }
            } else {
                fail = connect(net, t, wide, Mode.BOTH);
            }
            if (fail != null) {
                String second = connect(net, t, wide, Mode.TIE_IN_ONLY);
                if (second != null) {
                    net.unconnected.put(t, fail);
                    log.info("{}: цель {} не подключена: {}", variantId, t.id, fail);
                }
            }
        }
        String problem = TreeCalc.assign(net);
        if (problem != null) {
            throw new IllegalStateException("итоговая сеть " + variantId + " некорректна: " + problem);
        }
        improve(net, order, wide, singleEntry);
        return net;
    }

    /** Сколько проходов «снять и перепроложить». */
    static final int IMPROVE_ROUNDS = 2;
    /**
     * Страховочный лимит времени на улучшение одного варианта, мс. Число проходов фиксировано (IMPROVE_ROUNDS),
     * поэтому результат не зависит от скорости стенда; лимит срабатывает только на очень больших входах
     * и тогда отмечается в diag_search_truncated.
     */
    static final long IMPROVE_BUDGET_MS = 120_000;

    /**
     * Улучшение: каждая подключённая цель снимается вместе со своей веткой и присоединяется заново
     * при уже построенной остальной сети; изменение остаётся, только если показатель S уменьшился.
     */
    private void improve(Net net, List<Target> order, int wide, boolean singleEntry) {
        long t0 = System.nanoTime();
        for (int round = 0; round < IMPROVE_ROUNDS; round++) {
            boolean changed = false;
            for (Target t : order) {
                if ((System.nanoTime() - t0) / 1_000_000 > IMPROVE_BUDGET_MS) {
                    log.info("{}: улучшение остановлено по бюджету {} мс", net.variantId, IMPROVE_BUDGET_MS);
                    net.searchTruncated = true;
                    return;
                }
                if (net.unconnected.containsKey(t)) {
                    continue;
                }
                double before = VariantSummary.of(net, m.targets).score;
                Net.Snapshot snap = net.snapshot();
                if (!net.removeBranch(net.target(t)) || TreeCalc.assign(net) != null) {
                    net.restore(snap);
                    continue;
                }
                String fail = connect(net, t, wide, singleEntry && !net.lines.isEmpty() ? Mode.BRANCH_ONLY : Mode.BOTH);
                if (fail != null && singleEntry) {
                    fail = connect(net, t, wide, Mode.BOTH);
                }
                if (fail != null || TreeCalc.assign(net) != null
                        || VariantSummary.of(net, m.targets).score >= before - 1e-9) {
                    net.restore(snap);
                    TreeCalc.assign(net);
                    continue;
                }
                changed = true;
            }
            if (!changed) {
                break;
            }
        }
    }

    /**
     * Запасное присоединение для варианта «каждая отдельно» ({@link IndividualSolver}): цель, которой отдельная
     * трасса недоступна (например, строгий выход перекрыт трассами других целей, DECISIONS №32), присоединяется
     * к существующей сети или к уже построенной новой — по тем же правилам, что в совместной сети.
     * @return null при успехе или причина неудачи; при неудаче сеть не меняется
     */
    String attachLeftover(Net net, Target t) {
        int own = DiameterTable.minByFlow(t.flowTph).map(r -> r.dn).orElse(-1);
        if (own < 0) {
            return "расход " + t.flowTph + " т/ч больше пропускной способности наибольшего ДУ";
        }
        return connect(net, t, own, Mode.BOTH);
    }

    /** @return null при успехе или причина неудачи; при неудаче сеть не меняется. */
    private String connect(Net net, Target t, int wideDn, Mode mode) {
        int own = DiameterTable.minByFlow(t.flowTph).map(r -> r.dn).orElse(-1);
        if (own < 0) {
            return "расход " + t.flowTph + " т/ч больше пропускной способности наибольшего ДУ";
        }
        Set<Integer> dns = new LinkedHashSet<>();
        dns.add(Math.max(wideDn, own));
        dns.add(own);
        String last = "нет допустимой трассы";
        // сначала строгий финальный подход при любом ДУ, затем нестрогий (DECISIONS №27)
        for (boolean relaxedOk : new boolean[]{false, true}) {
        for (int dn : dns) {
            Net.Snapshot snap = net.snapshot();
            NetworkState state = new NetworkState(m.gf, net);
            Router.GoalProvider goals;
            if (mode == Mode.TIE_IN_ONLY || net.lines.isEmpty()) {
                goals = new TieInGoals(m, dn, state);
            } else if (mode == Mode.BRANCH_ONLY) {
                goals = new BranchGoals(m, net, t.flowTph);
            } else {
                goals = new CombinedGoals(new TieInGoals(m, dn, state), new BranchGoals(m, net, t.flowTph));
            }
            Router.Result r = pool.forDn(dn).route(t, m.ownOksOf(t), state.dynamicFor(dn), goals, relaxedOk, pool.forDn(own));
            if (r.route == null) {
                last = r.failure + " (ДУ " + dn + ")";
                continue;
            }
            List<Net.Line> added = Attacher.apply(net, t, r.route, dn);
            for (Net.Line l : added) {
                certify(net, l, wideDn);
            }
            String problem = TreeCalc.assign(net);
            // ДУ участка вырос сверх проверенного габарита — перестраиваем его цепочку с новым ДУ (до 3 раз)
            for (int rebuild = 0; problem == null && rebuild < 3; rebuild++) {
                Net.Line over = null;
                for (Net.Line l : net.lines) {
                    if (l.dn > l.routedDn) {
                        over = l;
                        break;
                    }
                }
                if (over == null) {
                    break;
                }
                if (!rerouteChain(net, over, over.dn, wideDn)) {
                    problem = "после объединения ДУ участка " + over.id + " (" + over.dn + ") больше проверенного габарита ДУ "
                            + over.routedDn + ", перестроить участок не удалось";
                    break;
                }
                problem = TreeCalc.assign(net);
            }
            if (problem == null) {
                for (Net.Line l : net.lines) {
                    if (l.dn > l.routedDn) {
                        problem = "после объединения ДУ участка " + l.id + " (" + l.dn + ") больше проверенного габарита ДУ "
                                + l.routedDn;
                        break;
                    }
                }
            }
            if (problem == null) {
                return null;
            }
            net.restore(snap);
            last = problem;
        }
        }
        return last;
    }

    /**
     * Наибольший ДУ (до wide), с габаритом которого участок остаётся допустимым по входным ограничениям:
     * тогда рост расхода от последующих присоединений не требует отката. Спецучастки не перепроверяются
     * (границы спецпрохода зависят от ДУ).
     */
    private void certify(Net net, Net.Line l, int wideDn) {
        if (l.special) {
            return;
        }
        java.util.Set<ru.lct.heatnet.model.InputObjects.Restriction> own = l.b.kind == Net.NodeKind.TARGET
                ? new java.util.HashSet<>(m.ownOksOf(l.b.target)) : java.util.Collections.emptySet();
        Coordinate exempt = l.a.tieIn ? l.a.xy : null;
        for (DiameterTable.Row row : DiameterTable.ROWS) {
            if (row.dn <= l.routedDn) {
                continue;
            }
            if (row.dn > wideDn) {
                break;
            }
            Router router = pool.forDn(row.dn);
            ru.lct.heatnet.routing.DynamicObstacles others = othersFor(net, l, row.dn);
            java.util.List<Coordinate> ends = java.util.Arrays.asList(l.a.xy, l.b.xy);
            boolean ok = true;
            for (int i = 0; i + 1 < l.coords.size() && ok; i++) {
                boolean finalSeg = i + 2 == l.coords.size() && !own.isEmpty();
                boolean first = i == 0;
                ok = router.checkSegment(l.coords.get(i), l.coords.get(i + 1),
                        finalSeg ? own : java.util.Collections.emptySet(), first ? exempt : null) != null
                        && others.freeExcept(m.gf.createLineString(new Coordinate[]{l.coords.get(i), l.coords.get(i + 1)}),
                        ends, TieInGoals.SHARED_NODE_RADIUS);
            }
            if (!ok) {
                break;
            }
            l.routedDn = row.dn;
        }
    }

    /**
     * Перестройка цепочки (участки между узлами смены расхода), в которую входит участок: снять и проложить заново
     * с ДУ dn между теми же узлами (§2.3 «рост ДУ сломал габарит → перестройка», план v3). @return успех.
     */
    private boolean rerouteChain(Net net, Net.Line line, int dn, int wideDn) {
        TreeCalc.Tree tree = TreeCalc.orient(net);
        java.util.Map<Net.Node, List<Net.Line>> adj = TreeCalc.adjacency(net);
        List<Net.Line> chain = new ArrayList<>();
        List<Net.Node> inner = new ArrayList<>();
        // вверх до узла смены расхода
        Net.Line cur = line;
        Net.Node upper;
        while (true) {
            chain.add(0, cur);
            Net.Node top = cur.other(tree.down.get(cur));
            if (passThrough(top, adj)) {
                inner.add(top);
                cur = tree.parent.get(cur);
                if (cur == null) {
                    return false;
                }
            } else {
                upper = top;
                break;
            }
        }
        // вниз до узла смены расхода
        cur = line;
        Net.Node lower;
        while (true) {
            Net.Node bottom = tree.down.get(cur);
            if (passThrough(bottom, adj)) {
                inner.add(bottom);
                Net.Line prev = cur;
                cur = adj.get(bottom).get(0) == prev ? adj.get(bottom).get(1) : adj.get(bottom).get(0);
                chain.add(cur);
            } else {
                lower = bottom;
                break;
            }
        }
        net.lines.removeAll(chain);
        net.nodes.removeAll(inner);
        NetworkState state = new NetworkState(m.gf, net);
        ru.lct.heatnet.routing.DynamicObstacles dyn = state.dynamicFor(dn);
        Router.Goal goal = new Router.Goal(upper.xy, 0, upper.tieIn ? upper.xy : null, null, TieInGoals.SHARED_NODE_RADIUS);
        Router.GoalProvider single = new Router.GoalProvider() {
            @Override
            public List<Router.Goal> near(Coordinate c) {
                return java.util.Collections.singletonList(goal);
            }

            @Override
            public double lowerBoundDistance(Coordinate c) {
                return c.distance(upper.xy);
            }
        };
        Router router = pool.forDn(dn);
        Router.Result res;
        if (lower.kind == Net.NodeKind.TARGET) {
            Target t = lower.target;
            int own = DiameterTable.minByFlow(t.flowTph).map(r -> r.dn).orElse(dn);
            res = router.route(t, m.ownOksOf(t), dyn, single, true, pool.forDn(own));
            if (res.route != null) {
                if (res.route.relaxedApproach) {
                    net.relaxedApproach.add(t);
                } else {
                    net.relaxedApproach.remove(t);
                }
            }
        } else {
            Target pseudo = new Target(ru.lct.heatnet.model.FeatureId.of("chain"), 0, lower.xy,
                    m.gf.createPoint(lower.xy));
            res = router.routeFromNode(pseudo, dyn, single, TieInGoals.SHARED_NODE_RADIUS);
        }
        if (res.route == null) {
            return false;
        }
        for (Net.Line l : Attacher.buildChain(net, upper, lower, res.route, dn, line.flow)) {
            certify(net, l, wideDn);
        }
        return true;
    }

    private static boolean passThrough(Net.Node n, java.util.Map<Net.Node, List<Net.Line>> adj) {
        return adj.get(n).size() == 2 && !n.tieIn && n.kind != Net.NodeKind.TARGET;
    }

    /** Габариты остальных новых линий для проверки участка l с ДУ dn (DECISIONS №23). */
    private ru.lct.heatnet.routing.DynamicObstacles othersFor(Net net, Net.Line l, int dn) {
        ru.lct.heatnet.routing.DynamicObstacles o = new ru.lct.heatnet.routing.DynamicObstacles(m.gf);
        double hw = DiameterTable.require(dn).halfWidth();
        for (Net.Line x : net.lines) {
            if (x != l) {
                double r = hw + DiameterTable.require(Math.max(x.dn, x.routedDn)).halfWidth() + NetworkState.GAP;
                o.add(m.gf.createLineString(x.coords.toArray(new Coordinate[0])), r);
            }
        }
        return o;
    }

    /** Объединение двух наборов целей поиска. */
    static final class CombinedGoals implements Router.GoalProvider {
        private final Router.GoalProvider a;
        private final Router.GoalProvider b;

        CombinedGoals(Router.GoalProvider a, Router.GoalProvider b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public List<Router.Goal> near(Coordinate c) {
            List<Router.Goal> out = new ArrayList<>(a.near(c));
            out.addAll(b.near(c));
            return out;
        }

        @Override
        public double lowerBoundDistance(Coordinate c) {
            return Math.min(a.lowerBoundDistance(c), b.lowerBoundDistance(c));
        }

        @Override
        public List<Router.Goal> onSegment(Coordinate p, Coordinate q) {
            List<Router.Goal> out = new ArrayList<>(a.onSegment(p, q));
            out.addAll(b.onSegment(p, q));
            return out;
        }
    }
}
