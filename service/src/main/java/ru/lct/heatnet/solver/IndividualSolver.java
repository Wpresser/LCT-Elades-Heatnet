package ru.lct.heatnet.solver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.routing.Router;
import ru.lct.heatnet.rules.DiameterTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Вариант «каждая цель отдельно»: у каждой точки подключения своя трасса и своё присоединение.
 * Цели обрабатываются по возрастанию расстояния до сети; уже построенные трассы — препятствия.
 * ДУ — минимальный по расходу; если трасса длиннее предельной длины, ДУ увеличивается и трасса
 * строится заново с новым габаритом (§2.3).
 */
public final class IndividualSolver {

    private static final Logger log = LoggerFactory.getLogger(IndividualSolver.class);

    private final RouterPool pool;
    private final InputModel m;

    public IndividualSolver(RouterPool pool) {
        this.pool = pool;
        this.m = pool.model();
    }

    public Net solve(String variantId, List<Target> order) {
        return solve(variantId, order, Long.MAX_VALUE);
    }

    /** @return null, если не уложились в бюджет времени (вариант не выдаётся). */
    public Net solve(String variantId, List<Target> order, long budgetMs) {
        long t0 = System.nanoTime();
        Net net = new Net(variantId);
        net.description = "каждая точка подключения присоединяется отдельно";
        for (Target t : order) {
            if ((System.nanoTime() - t0) / 1_000_000 > budgetMs) {
                log.info("{}: вариант не выдаётся — превышен бюджет {} мс", variantId, budgetMs);
                return null;
            }
            NetworkState state = new NetworkState(m.gf, net);
            Attempt a = routeTarget(t, state);
            if (a.route == null) {
                net.unconnected.put(t, a.failure);
                log.info("{}: цель {} не подключена: {}", variantId, t.id, a.failure);
                continue;
            }
            Attacher.apply(net, t, a.route, a.dn);
        }
        String problem = TreeCalc.assign(net);
        if (problem != null) {
            throw new IllegalStateException("итоговая сеть " + variantId + " некорректна: " + problem);
        }
        attachLeftovers(net);
        return net;
    }

    /**
     * §2.5: неподключение допустимо, только если допустимого маршрута нет. Цели, которым отдельная трасса
     * недоступна, присоединяются к существующей или уже построенной новой сети (как в совместном варианте).
     */
    private void attachLeftovers(Net net) {
        if (net.unconnected.isEmpty()) {
            return;
        }
        TreeSolver tree = new TreeSolver(pool);
        boolean attached = false;
        for (Target t : new ArrayList<>(net.unconnected.keySet())) {
            String reason = net.unconnected.remove(t);
            String fail = tree.attachLeftover(net, t);
            if (fail != null) {
                net.unconnected.put(t, reason);
                continue;
            }
            attached = true;
            log.info("{}: цель {} присоединена к построенной сети (отдельная трасса невозможна: {})",
                    net.variantId, t.id, reason);
        }
        if (attached) {
            net.description = "каждая точка подключения присоединяется отдельно; если отдельная трасса невозможна — "
                    + "к уже построенной сети";
        }
        String problem = TreeCalc.assign(net);
        if (problem != null) {
            throw new IllegalStateException("итоговая сеть " + net.variantId + " некорректна: " + problem);
        }
    }

    static final class Attempt {
        Router.Route route;
        int dn;
        String failure;
    }

    Attempt routeTarget(Target t, NetworkState state) {
        Attempt a = new Attempt();
        Optional<DiameterTable.Row> row = DiameterTable.minByFlow(t.flowTph);
        if (!row.isPresent()) {
            a.failure = "расход " + t.flowTph + " т/ч больше пропускной способности наибольшего ДУ";
            return a;
        }
        int dn = row.get().dn;
        for (int attempt = 0; attempt < 6; attempt++) {
            Router router = pool.forDn(dn);
            Router.Result r = router.route(t, m.ownOksOf(t), state.dynamicFor(dn), new TieInGoals(m, dn, state));
            if (r.route == null) {
                a.failure = r.failure + " (ДУ " + dn + ")";
                return a;
            }
            if (r.route.length <= DiameterTable.require(dn).maxLength) {
                a.route = r.route;
                a.dn = dn;
                return a;
            }
            Optional<DiameterTable.Row> next = DiameterTable.minByFlowAndLength(t.flowTph, r.route.length);
            if (!next.isPresent()) {
                a.failure = "трасса " + Math.round(r.route.length) + " м длиннее предельной длины любого ДУ";
                return a;
            }
            dn = next.get().dn;
        }
        a.failure = "не удалось согласовать ДУ и предельную длину";
        return a;
    }

    /** Порядок обработки: ближе к сети — раньше. */
    public static List<Target> byDistance(InputModel m) {
        List<Target> order = new ArrayList<>(m.targets);
        TieInGoals probe = new TieInGoals(m, 50, new NetworkState(m.gf, new Net("probe")));
        order.sort(Comparator.comparingDouble((Target t) -> probe.lowerBoundDistance(t.point.getCoordinate()))
                .thenComparing(t -> t.id.toString()));
        return order;
    }
}
