package ru.lct.heatnet.rules;

import java.util.Locale;
import java.util.Optional;

/**
 * Правила таблицы 2 техприложения по типу ограничения.
 * Пересечение существующей тепловой сети без врезки — тоже спецпроход, см. {@link #EXISTING_HEAT_NETWORK}.
 */
public enum RestrictionRule {

    OKS("oks", Kind.FORBIDDEN, Double.NaN, 0, 0, 1.0, 0),
    PARK("park", Kind.FORBIDDEN, 1.0, 0, 0, 1.0, 0),
    SOCIAL_AREA("social_area", Kind.FORBIDDEN, 1.0, 0, 0, 1.0, 0),
    PROHIBITED_SITE("prohibited_site", Kind.FORBIDDEN, 1.0, 0, 0, 1.0, 0),
    WATER("water", Kind.FORBIDDEN, 1.0, 0, 0, 1.0, 0),
    RAILWAY("railway", Kind.FORBIDDEN, 1.0, 0, 0, 1.0, 0),
    /** Полигон дороги и по 3 м за границей с каждой стороны, угол ≥ 45°. */
    ROAD("road", Kind.SPECIAL_AREA, 1.5, 45, 3.0, 1.60, 0),
    TRAM_TRACKS("tram_tracks", Kind.SPECIAL_AREA, 1.5, 45, 3.0, 1.75, 0),
    /** По 2 м с каждой стороны точки пересечения; собственный габарит 0,40 м. */
    GAS_PIPELINE("gas_pipeline", Kind.SPECIAL_CROSSING, 2.0, 0, 2.0, 1.25, 0.40),
    /** Собственный габарит 0,20 м. */
    POWER_CABLE("power_cable", Kind.SPECIAL_CROSSING, 2.0, 0, 2.0, 1.15, 0.20),
    /** Существующая теплосеть без врезки: габарит по таблице 1 по её ДУ. */
    EXISTING_HEAT_NETWORK("heat_network", Kind.SPECIAL_CROSSING, 1.0, 0, 2.0, 1.05, Double.NaN);

    public enum Kind {
        /** Пересечение запрещено, обход с отступом. */
        FORBIDDEN,
        /** Спецпроход через площадной объект: весь полигон + запас за границей. */
        SPECIAL_AREA,
        /** Спецпроход в точке пересечения с линейным объектом ± запас. */
        SPECIAL_CROSSING
    }

    public final String code;
    public final Kind kind;
    private final double clearance;
    /** Минимальный угол пересечения, град.; 0 — не задан. */
    public final double minAngleDeg;
    /** Граница спецучастка: запас за границей полигона или от точки пересечения, м. */
    public final double specialMargin;
    public final double kSpec;
    /** Собственный расчётный габарит (ширина), м; 0 — нет, NaN — по ДУ существующей сети. */
    public final double ownWidth;

    RestrictionRule(String code, Kind kind, double clearance, double minAngleDeg, double specialMargin,
                    double kSpec, double ownWidth) {
        this.code = code;
        this.kind = kind;
        this.clearance = clearance;
        this.minAngleDeg = minAngleDeg;
        this.specialMargin = specialMargin;
        this.kSpec = kSpec;
        this.ownWidth = ownWidth;
    }

    /** Минимальное горизонтальное расстояние между внешними габаритами для новой сети с данным ДУ. */
    public double clearance(int newDn) {
        if (this == OKS) {
            if (newDn < 500) {
                return 5.0;
            }
            if (newDn <= 800) {
                return 7.0;
            }
            return 9.0;
        }
        return clearance;
    }

    public boolean isSpecial() {
        return kind != Kind.FORBIDDEN;
    }

    /** Тип ограничения из входа; empty — тип не из таблицы 2 (поддержка необязательна). */
    public static Optional<RestrictionRule> byCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (RestrictionRule r : values()) {
            if (r != EXISTING_HEAT_NETWORK && r.code.equals(c)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
