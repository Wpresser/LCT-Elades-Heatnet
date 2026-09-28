package ru.lct.heatnet.rules;

/** Стоимость камер и врезок (§3.2), штраф и итоговый показатель (§6). */
public final class Costs {

    public static final double EXISTING_CHAMBER_TIE_IN = 5_000_000;
    public static final double UNCONNECTED_BASE = 100_000_000;
    public static final double UNCONNECTED_PER_TPH = 500_000;
    public static final double SCORE_COST_NORM = 25_000_000;
    public static final double SCORE_LENGTH_NORM = 100;

    private Costs() {
    }

    /** Стоимость новой камеры по наибольшему ДУ примыкающих участков. */
    public static double newChamber(int maxDn) {
        if (maxDn <= 200) {
            return 3_000_000;
        }
        if (maxDn <= 500) {
            return 5_000_000;
        }
        if (maxDn <= 1000) {
            return 8_000_000;
        }
        return 12_000_000;
    }

    public static double unconnectedPenalty(double flowTph) {
        return UNCONNECTED_BASE + UNCONNECTED_PER_TPH * flowTph;
    }

    /** S = 0,7 · C / 25 000 000 + 0,3 · L / 100. */
    public static double score(double calculatedCost, double newNetworkLength) {
        return 0.7 * (calculatedCost / SCORE_COST_NORM) + 0.3 * (newNetworkLength / SCORE_LENGTH_NORM);
    }

    /** Стоимость участка: L · c(ДУ) · Kспец (в 2D Kгл = 1). */
    public static double segment(double length, int dn, double kSpec) {
        return length * DiameterTable.require(dn).pricePerM * kSpec;
    }
}
