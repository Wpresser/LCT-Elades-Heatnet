package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.model.InputObjects.ExistingChamber;
import ru.lct.heatnet.routing.DynamicObstacles;
import ru.lct.heatnet.rules.DiameterTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Что уже построено в варианте: занятые примыкания существующих камер, новые камеры, линии-препятствия.
 * Собирается заново из сети — после отката ничего не нужно синхронизировать.
 */
final class NetworkState {

    /** Запас между габаритами новых линий, м. */
    static final double GAP = 0.05;

    private final GeometryFactory gf;
    private final Net net;
    private final Map<ExistingChamber, Integer> linesAtChamber = new IdentityHashMap<>();
    final List<Coordinate> newChamberPoints = new ArrayList<>();
    private final Map<Integer, DynamicObstacles> dynamicByDn = new HashMap<>();

    NetworkState(GeometryFactory gf, Net net) {
        this.gf = gf;
        this.net = net;
        for (Net.Node n : net.nodes) {
            if (n.kind == Net.NodeKind.EXISTING_CHAMBER) {
                linesAtChamber.put(n.chamber, net.linesAt(n).size());
            } else if (n.kind == Net.NodeKind.NEW_CHAMBER) {
                newChamberPoints.add(n.xy);
            }
        }
    }

    int linesAt(ExistingChamber c) {
        return linesAtChamber.getOrDefault(c, 0);
    }

    boolean hasRoom(ExistingChamber c) {
        return c.existingAdjacency + linesAt(c) + 1 <= 4;
    }

    /** Построенные линии как препятствия для новой трассы ДУ dn: габарит обеих + запас. */
    DynamicObstacles dynamicFor(int dn) {
        return dynamicByDn.computeIfAbsent(dn, d -> {
            DynamicObstacles o = new DynamicObstacles(gf);
            double hw = DiameterTable.require(d).halfWidth();
            for (Net.Line l : net.lines) {
                double r = hw + DiameterTable.require(Math.max(l.dn, l.routedDn)).halfWidth() + GAP;
                o.add(gf.createLineString(l.coords.toArray(new Coordinate[0])), r);
            }
            return o;
        });
    }
}
