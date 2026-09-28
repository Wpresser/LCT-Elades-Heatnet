package ru.lct.heatnet.rules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Таблица 1 техприложения: ДУ, пропускная способность, предельная длина, цена, габарит. */
public final class DiameterTable {

    public static final class Row {
        public final int dn;
        /** т/ч */
        public final double capacity;
        /** м */
        public final double maxLength;
        /** руб./м */
        public final double pricePerM;
        /** м */
        public final double width;
        /** м */
        public final double height;

        Row(int dn, double capacity, double maxLength, double pricePerM, double width, double height) {
            this.dn = dn;
            this.capacity = capacity;
            this.maxLength = maxLength;
            this.pricePerM = pricePerM;
            this.width = width;
            this.height = height;
        }

        public double halfWidth() {
            return width / 2.0;
        }
    }

    public static final List<Row> ROWS = Collections.unmodifiableList(Arrays.asList(
            new Row(50, 3.5, 181, 74_023, 0.400, 0.125),
            new Row(65, 8.3, 245, 78_631, 0.430, 0.140),
            new Row(80, 13.2, 327, 83_530, 0.470, 0.160),
            new Row(100, 22.3, 419, 89_748, 0.510, 0.180),
            new Row(125, 40.2, 554, 97_275, 0.600, 0.225),
            new Row(150, 65.1, 696, 105_507, 0.650, 0.250),
            new Row(200, 152.3, 1042, 120_275, 0.880, 0.315),
            new Row(250, 274.9, 1379, 135_323, 1.050, 0.400),
            new Row(300, 437.4, 1718, 150_022, 1.150, 0.450),
            new Row(400, 943.1, 2477, 190_299, 1.370, 0.560),
            new Row(500, 1663.4, 3245, 224_137, 1.670, 0.710),
            new Row(600, 2627.7, 4037, 264_790, 1.850, 0.800),
            new Row(700, 3735.1, 4775, 324_298, 2.050, 0.900),
            new Row(800, 5296.8, 5644, 325_996, 2.250, 1.000),
            new Row(900, 7165.0, 6518, 327_693, 2.450, 1.100),
            new Row(1000, 9391.8, 7419, 418_777, 2.650, 1.200),
            new Row(1200, 15012.8, 9288, 428_074, 3.100, 1.425),
            new Row(1400, 22501.9, 11276, 683_417, 3.450, 1.600)
    ));

    private DiameterTable() {
    }

    public static Optional<Row> byDn(int dn) {
        return ROWS.stream().filter(r -> r.dn == dn).findFirst();
    }

    public static Row require(int dn) {
        return byDn(dn).orElseThrow(() -> new IllegalArgumentException("ДУ нет в таблице 1: " + dn));
    }

    /** Минимальный ДУ, пропускающий расход; empty — если расход больше максимального по таблице. */
    public static Optional<Row> minByFlow(double flowTph) {
        return ROWS.stream().filter(r -> r.capacity >= flowTph).findFirst();
    }

    /** Минимальный ДУ, пропускающий расход и допускающий непрерывный путь заданной длины. */
    public static Optional<Row> minByFlowAndLength(double flowTph, double pathLength) {
        return ROWS.stream().filter(r -> r.capacity >= flowTph && r.maxLength >= pathLength).findFirst();
    }

    /** Следующий по таблице ДУ. */
    public static Optional<Row> next(int dn) {
        for (int i = 0; i < ROWS.size() - 1; i++) {
            if (ROWS.get(i).dn == dn) {
                return Optional.of(ROWS.get(i + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * Ширина пары для существующей трубы. ДУ существующей сети может отсутствовать в таблице —
     * тогда берём ближайший не меньший ДУ (консервативно шире), а если больше всех — максимальный.
     */
    public static double widthForExisting(int dn) {
        return ROWS.stream().filter(r -> r.dn >= dn).findFirst().orElse(ROWS.get(ROWS.size() - 1)).width;
    }

    public static Row max() {
        return ROWS.get(ROWS.size() - 1);
    }
}
