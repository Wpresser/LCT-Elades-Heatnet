package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.model.InputObjects.Target;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Расходы и ДУ по дереву (§2.3, разъяснения №1–2). */
class TreeCalcTest {

    private final GeometryFactory gf = new GeometryFactory();

    private Target target(long id, double flow, double x, double y) {
        return new Target(FeatureId.of(id), flow, new Coordinate(0, 0), gf.createPoint(new Coordinate(x, y)));
    }

    private Net.Line line(Net net, Net.Node a, Net.Node b) {
        return net.addLine(a, b, Arrays.asList(a.xy, b.xy), false, 1.0, java.util.Collections.emptyList());
    }

    private Net.Node root(Net net) {
        Net.Node r = net.newChamber(new Coordinate(0, 0));
        r.tieIn = true;
        return r;
    }

    @Test
    void singleChainTakesMinimalDiameter() {
        Net net = new Net("t");
        Net.Line l = line(net, root(net), net.target(target(1, 20, 0, 100)));
        assertThat(TreeCalc.assign(net)).isNull();
        assertThat(l.flow).isEqualTo(20);
        assertThat(l.dn).isEqualTo(100);
    }

    @Test
    void longChainTakesNextDiameter() {
        Net net = new Net("t");
        // ДУ 100 допускает 419 м, путь 500 м → ДУ 125 (554 м)
        Net.Line l = line(net, root(net), net.target(target(1, 20, 0, 500)));
        assertThat(TreeCalc.assign(net)).isNull();
        assertThat(l.dn).isEqualTo(125);
    }

    @Test
    void branchSumsFlowsAndDiameterGrowsTowardsTieIn() {
        Net net = new Net("t");
        Net.Node r = root(net);
        Net.Node b = net.newChamber(new Coordinate(0, 30));
        Net.Line trunk = line(net, r, b);
        Net.Line l1 = line(net, b, net.target(target(1, 30, -30, 30)));
        Net.Line l2 = line(net, b, net.target(target(2, 20, 30, 30)));
        assertThat(TreeCalc.assign(net)).isNull();
        assertThat(trunk.flow).isEqualTo(50);
        assertThat(trunk.dn).isEqualTo(150);
        assertThat(l1.dn).isEqualTo(125);
        assertThat(l2.dn).isEqualTo(100);
    }

    @Test
    void constantFlowChainKeepsOneDiameterThroughTechnicalNode() {
        Net net = new Net("t");
        Net.Node r = root(net);
        Net.Node tn = net.techNode(new Coordinate(0, 300));
        Net.Line a = line(net, r, tn);
        Net.Line b = line(net, tn, net.target(target(1, 20, 0, 500)));
        assertThat(TreeCalc.assign(net)).isNull();
        // цепочка 500 м одним расходом: ДУ одинаковый на обоих участках, по длине — 125
        assertThat(a.dn).isEqualTo(125);
        assertThat(b.dn).isEqualTo(125);
    }

    @Test
    void sameDiameterRunAcrossBranchCountsWholePath() {
        Net net = new Net("t");
        Net.Node r = root(net);
        Net.Node b = net.newChamber(new Coordinate(0, 400));
        Net.Line trunk = line(net, r, b);                                   // 400 м, расход 51 → ДУ 150
        Net.Line leaf = line(net, b, net.target(target(1, 50, 0, 800)));   // 400 м, расход 50 → ДУ 150
        line(net, b, net.target(target(2, 1, 10, 400)));                     // короткая ветка 1 т/ч
        assertThat(TreeCalc.assign(net)).isNull();
        // путь цели 1: 800 м подряд ДУ 150 > 696 → верхняя цепочка (магистраль) берёт следующий ДУ
        assertThat(leaf.dn).isEqualTo(150);
        assertThat(trunk.dn).isEqualTo(200);
    }

    @Test
    void cycleIsReported() {
        Net net = new Net("t");
        Net.Node r = root(net);
        Net.Node b = net.newChamber(new Coordinate(0, 30));
        Net.Node c = net.newChamber(new Coordinate(30, 30));
        line(net, r, b);
        line(net, b, c);
        line(net, c, r);
        line(net, c, net.target(target(1, 10, 60, 30)));
        assertThat(TreeCalc.assign(net)).isNotNull();
    }
}
