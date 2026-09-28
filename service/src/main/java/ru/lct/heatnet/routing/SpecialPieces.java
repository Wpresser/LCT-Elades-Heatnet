package ru.lct.heatnet.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Разбиение участков спецпрохода на куски с постоянным набором ограничений (разъяснение №8):
 * на границах смены набора начинается новый участок, Kспец — наибольший из набора.
 */
public final class SpecialPieces {

    public static final class Piece {
        public final double from;
        public final double to;
        public final double k;
        public final List<SpecialSpan> active;

        Piece(double from, double to, double k, List<SpecialSpan> active) {
            this.from = from;
            this.to = to;
            this.k = k;
            this.active = active;
        }
    }

    private SpecialPieces() {
    }

    public static List<Piece> split(List<SpecialSpan> spans) {
        TreeSet<Double> cuts = new TreeSet<>();
        for (SpecialSpan s : spans) {
            cuts.add(s.from);
            cuts.add(s.to);
        }
        List<Piece> out = new ArrayList<>();
        Double prev = null;
        for (double c : cuts) {
            if (prev != null && c - prev > 1e-9) {
                double mid = (prev + c) / 2;
                List<SpecialSpan> act = new ArrayList<>();
                double k = 0;
                for (SpecialSpan s : spans) {
                    if (s.from <= mid && mid <= s.to) {
                        act.add(s);
                        k = Math.max(k, s.rule.kSpec);
                    }
                }
                if (!act.isEmpty()) {
                    out.add(new Piece(prev, c, k, act));
                }
            }
            prev = c;
        }
        return out;
    }

    /** [from, to, k] для расчёта стоимости. */
    static List<double[]> pieces(List<SpecialSpan> spans) {
        List<double[]> out = new ArrayList<>();
        for (Piece p : split(spans)) {
            out.add(new double[]{p.from, p.to, p.k});
        }
        return out;
    }
}
