package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.routing.Router;
import ru.lct.heatnet.routing.SpecialSpan;
import ru.lct.heatnet.routing.TieIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Добавляет найденную трассу в сеть: от места присоединения (корень) к цели. */
final class Attacher {

    private Attacher() {
    }

    static List<Net.Line> apply(Net net, Target t, Router.Route route, int routedDn) {
        Net.Node root;
        Object payload = route.goal.payload;
        if (payload instanceof TieIn) {
            TieIn tie = (TieIn) payload;
            if (tie.kind == TieIn.Kind.EXISTING_CHAMBER) {
                root = net.existingChamber(tie.chamber);
            } else {
                root = net.newChamber(tie.point);
                root.tieIn = true;
                root.pipe = tie.pipe;
                root.existingMaxDn = tie.existingMaxDn;
            }
        } else {
            BranchGoals.Attach at = (BranchGoals.Attach) payload;
            if (at.chamber != null) {
                root = at.chamber;
            } else {
                root = net.newChamber(at.point);
                net.split(at.line, at.point, root);
            }
        }
        Net.Node leaf = net.target(t);
        if (route.relaxedApproach) {
            net.relaxedApproach.add(t);
        }
        return buildChain(net, root, leaf, route, routedDn, t.flowTph);
    }

    /** Участки по трассе от root к leaf (трасса найдена от leaf к root). */
    static List<Net.Line> buildChain(Net net, Net.Node root, Net.Node leaf, Router.Route route, int routedDn, double flow) {
        List<Coordinate> pts = new ArrayList<>(route.points);
        Collections.reverse(pts);
        List<List<SpecialSpan>> spans = new ArrayList<>();
        for (int i = route.spans.size() - 1; i >= 0; i--) {
            double len = route.points.get(i).distance(route.points.get(i + 1));
            List<SpecialSpan> rev = new ArrayList<>();
            for (SpecialSpan s : route.spans.get(i)) {
                rev.add(s.reversed(len));
            }
            spans.add(rev);
        }
        pts = RouteGeometry.dropStraightVertices(pts, spans);
        List<Net.Line> lines = ChainBuilder.build(net, root, leaf, pts, spans);
        for (Net.Line l : lines) {
            l.flow = flow;
            l.dn = routedDn;
            l.routedDn = routedDn;
        }
        return lines;
    }
}
