package ru.lct.heatnet.solver;

import ru.lct.heatnet.rules.DiameterTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Расходы и ДУ по дереву новой сети (§2.3, разъяснения №1–2):
 * расход участка — сумма flow_tph целей ниже по течению; на цепочке между узлами смены расхода ДУ постоянен;
 * ДУ — минимальный по расходу, к присоединению не убывает; если непрерывный путь одного ДУ длиннее предельной
 * длины, верхняя цепочка этого пути берёт следующий ДУ (с ней начинается новый отсчёт).
 */
final class TreeCalc {

    /** Ориентация дерева от присоединений. */
    static final class Tree {
        final Map<Net.Line, Net.Node> down = new IdentityHashMap<>();
        final Map<Net.Line, Net.Line> parent = new IdentityHashMap<>();
        final Map<Net.Node, Net.Line> lineAbove = new IdentityHashMap<>();
        final List<Net.Line> bfsOrder = new ArrayList<>();
    }

    private static final class Chain {
        final List<Net.Line> lines = new ArrayList<>();
        Chain parent;
        double flow;
        int dn;
        double length;
    }

    private TreeCalc() {
    }

    static Tree orient(Net net) {
        Tree t = new Tree();
        Map<Net.Node, List<Net.Line>> adj = adjacency(net);
        Deque<Net.Node> queue = new ArrayDeque<>();
        for (Net.Node n : net.nodes) {
            if (n.tieIn && adj.containsKey(n)) {
                queue.add(n);
                t.lineAbove.put(n, null);
            }
        }
        while (!queue.isEmpty()) {
            Net.Node n = queue.poll();
            for (Net.Line l : adj.getOrDefault(n, Collections.emptyList())) {
                if (t.down.containsKey(l)) {
                    continue;
                }
                Net.Node m = l.other(n);
                if (t.lineAbove.containsKey(m)) {
                    continue; // цикл или вторая точка присоединения — отлавливается проверкой
                }
                t.down.put(l, m);
                t.parent.put(l, t.lineAbove.get(n));
                t.lineAbove.put(m, l);
                t.bfsOrder.add(l);
                queue.add(m);
            }
        }
        return t;
    }

    static Map<Net.Node, List<Net.Line>> adjacency(Net net) {
        Map<Net.Node, List<Net.Line>> adj = new IdentityHashMap<>();
        for (Net.Line l : net.lines) {
            adj.computeIfAbsent(l.a, k -> new ArrayList<>()).add(l);
            adj.computeIfAbsent(l.b, k -> new ArrayList<>()).add(l);
        }
        return adj;
    }

    /** Назначить расходы и ДУ. @return null или описание проблемы. */
    static String assign(Net net) {
        Tree t = orient(net);
        if (t.down.size() != net.lines.size()) {
            return "часть участков не связана с присоединением или образует цикл";
        }
        Map<Net.Node, List<Net.Line>> adj = adjacency(net);
        // расходы: снизу вверх
        Map<Net.Line, Double> flow = new IdentityHashMap<>();
        List<Net.Line> order = new ArrayList<>(t.bfsOrder);
        Collections.reverse(order);
        for (Net.Line l : order) {
            Net.Node d = t.down.get(l);
            double f = d.kind == Net.NodeKind.TARGET ? d.target.flowTph : 0;
            for (Net.Line child : adj.get(d)) {
                if (child != l) {
                    f += flow.get(child);
                }
            }
            flow.put(l, f);
        }
        // цепочки постоянного расхода: внутренние узлы — проходные (два участка, не цель)
        Map<Net.Line, Chain> chainOf = new IdentityHashMap<>();
        List<Chain> chains = new ArrayList<>();
        for (Net.Line l : t.bfsOrder) {
            Net.Line p = t.parent.get(l);
            Net.Node top = l.other(t.down.get(l));
            boolean continues = p != null && adj.get(top).size() == 2 && top.kind != Net.NodeKind.TARGET && !top.tieIn;
            Chain c;
            if (continues) {
                c = chainOf.get(p);
            } else {
                c = new Chain();
                c.parent = p == null ? null : chainOf.get(p);
                c.flow = flow.get(l);
                chains.add(c);
            }
            c.lines.add(l);
            c.length += l.length();
            chainOf.put(l, c);
        }
        for (Chain c : chains) {
            Optional<DiameterTable.Row> row = DiameterTable.minByFlow(c.flow);
            if (!row.isPresent()) {
                return "расход " + c.flow + " т/ч больше пропускной способности наибольшего ДУ";
            }
            c.dn = row.get().dn;
        }
        propagateUp(chains);
        // предельная длина по каждому пути от цели к присоединению
        for (int iter = 0; iter < 200; iter++) {
            Chain bump = null;
            for (Chain leaf : chains) {
                if (!leafChain(leaf, chains)) {
                    continue;
                }
                Chain c = leaf;
                while (c != null && bump == null) {
                    Chain runTop = c;
                    double runLen = c.length;
                    while (runTop.parent != null && runTop.parent.dn == c.dn) {
                        runTop = runTop.parent;
                        runLen += runTop.length;
                    }
                    if (runLen > DiameterTable.require(c.dn).maxLength) {
                        bump = runTop;
                    }
                    c = runTop.parent;
                }
                if (bump != null) {
                    break;
                }
            }
            if (bump == null) {
                break;
            }
            Optional<DiameterTable.Row> next = DiameterTable.next(bump.dn);
            if (!next.isPresent()) {
                return "непрерывный путь длиннее предельной длины наибольшего ДУ";
            }
            bump.dn = next.get().dn;
            propagateUp(chains);
        }
        String left = lengthViolation(chains);
        if (left != null) {
            return left;
        }
        for (Chain c : chains) {
            for (Net.Line l : c.lines) {
                l.flow = c.flow;
                l.dn = c.dn;
            }
        }
        return null;
    }

    /** Итоговая проверка: нет непрерывного пути одного ДУ длиннее предельной длины. */
    private static String lengthViolation(List<Chain> chains) {
        for (Chain leaf : chains) {
            if (!leafChain(leaf, chains)) {
                continue;
            }
            for (Chain c = leaf; c != null; ) {
                Chain top = c;
                double len = c.length;
                while (top.parent != null && top.parent.dn == c.dn) {
                    top = top.parent;
                    len += top.length;
                }
                if (len > DiameterTable.require(c.dn).maxLength + 1e-6) {
                    return "не удалось подобрать ДУ по предельной длине (путь " + Math.round(len) + " м, ДУ " + c.dn + ")";
                }
                c = top.parent;
            }
        }
        return null;
    }

    private static boolean leafChain(Chain c, List<Chain> all) {
        for (Chain o : all) {
            if (o.parent == c) {
                return false;
            }
        }
        return true;
    }

    /** ДУ к присоединению не убывает. */
    private static void propagateUp(List<Chain> chains) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Chain c : chains) {
                if (c.parent != null && c.parent.dn < c.dn) {
                    c.parent.dn = c.dn;
                    changed = true;
                }
            }
        }
    }
}
