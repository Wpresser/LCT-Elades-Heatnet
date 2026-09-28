package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.rules.Costs;
import ru.lct.heatnet.rules.DiameterTable;
import ru.lct.heatnet.rules.RestrictionRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RulesTest {

    /** Пример техприложения §7.3: 100 м ДУ 100 + одна врезка в существующую камеру. */
    @Test
    void appendixExample() {
        double segment = Costs.segment(100, 100, 1.0);
        assertThat(segment).isCloseTo(8_974_800, within(1e-6));
        double construction = segment + Costs.EXISTING_CHAMBER_TIE_IN;
        assertThat(construction).isCloseTo(13_974_800, within(1e-6));
        assertThat(Costs.score(construction, 100)).isCloseTo(0.6912944, within(1e-9));
    }

    @Test
    void minimalDiameterByFlow() {
        assertThat(DiameterTable.minByFlow(20).get().dn).isEqualTo(100);
        assertThat(DiameterTable.minByFlow(22.3).get().dn).isEqualTo(100);
        assertThat(DiameterTable.minByFlow(22.31).get().dn).isEqualTo(125);
        assertThat(DiameterTable.minByFlow(488.72).get().dn).isEqualTo(400);
        assertThat(DiameterTable.minByFlow(30_000)).isEmpty();
        // 20 т/ч, но путь 500 м: ДУ 100 допускает 419 м → нужен ДУ 125 (554 м)
        assertThat(DiameterTable.minByFlowAndLength(20, 500).get().dn).isEqualTo(125);
    }

    @Test
    void chamberCostBands() {
        assertThat(Costs.newChamber(200)).isEqualTo(3_000_000);
        assertThat(Costs.newChamber(250)).isEqualTo(5_000_000);
        assertThat(Costs.newChamber(500)).isEqualTo(5_000_000);
        assertThat(Costs.newChamber(600)).isEqualTo(8_000_000);
        assertThat(Costs.newChamber(1200)).isEqualTo(12_000_000);
    }

    @Test
    void penaltyAndOksClearance() {
        assertThat(Costs.unconnectedPenalty(10)).isEqualTo(105_000_000);
        assertThat(RestrictionRule.OKS.clearance(400)).isEqualTo(5.0);
        assertThat(RestrictionRule.OKS.clearance(500)).isEqualTo(7.0);
        assertThat(RestrictionRule.OKS.clearance(800)).isEqualTo(7.0);
        assertThat(RestrictionRule.OKS.clearance(900)).isEqualTo(9.0);
        assertThat(RestrictionRule.byCode("Road")).contains(RestrictionRule.ROAD);
        assertThat(RestrictionRule.byCode("heat_network")).isEmpty();
        assertThat(RestrictionRule.byCode("metro")).isEmpty();
    }
}
