package ru.lct.heatnet.solver;

import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.rules.Costs;
import ru.lct.heatnet.rules.DiameterTable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Стоимость и показатель варианта (§3.2, §6, §7.2). */
public final class VariantSummary {

    public final Net net;
    public double pipeCost;
    public double chamberCost;
    public int tieInCount;
    public double tieInCost;
    public double penalty;
    public double construction;
    public double calculated;
    public double length;
    public double score;
    public int rank;
    public final List<Target> unconnected = new ArrayList<>();
    /** Диаметр и стоимость новых камер. */
    public final Map<Net.Node, int[]> chamberDn = new IdentityHashMap<>();
    public final Map<Net.Node, Double> chamberCosts = new IdentityHashMap<>();

    private VariantSummary(Net net) {
        this.net = net;
    }

    public static double lineCost(Net.Line l) {
        return l.length() * DiameterTable.require(l.dn).pricePerM * l.kSpec;
    }

    public static VariantSummary of(Net net, List<Target> allTargets) {
        VariantSummary s = new VariantSummary(net);
        for (Net.Line l : net.lines) {
            s.pipeCost += lineCost(l);
            s.length += l.length();
        }
        for (Net.Node n : net.nodes) {
            if (n.kind == Net.NodeKind.NEW_CHAMBER) {
                // наибольший ДУ всех примыкающих участков, включая существующие трубы (DECISIONS №19)
                int max = n.existingMaxDn;
                for (Net.Line l : net.linesAt(n)) {
                    max = Math.max(max, l.dn);
                }
                double c = Costs.newChamber(max);
                s.chamberDn.put(n, new int[]{max});
                s.chamberCosts.put(n, c);
                s.chamberCost += c;
            } else if (n.kind == Net.NodeKind.EXISTING_CHAMBER) {
                s.tieInCount += net.linesAt(n).size();
            }
        }
        s.tieInCost = s.tieInCount * Costs.EXISTING_CHAMBER_TIE_IN;
        for (Target t : allTargets) {
            if (net.unconnected.containsKey(t)) {
                s.unconnected.add(t);
                s.penalty += Costs.unconnectedPenalty(t.flowTph);
            }
        }
        s.construction = s.pipeCost + s.chamberCost + s.tieInCost;
        s.calculated = s.construction + s.penalty;
        s.score = Costs.score(s.calculated, s.length);
        return s;
    }
}
