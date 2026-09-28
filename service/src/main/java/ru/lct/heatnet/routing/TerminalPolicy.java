package ru.lct.heatnet.routing;

import java.util.Locale;

/**
 * Трактовка §2.2 «один финальный прямой участок от ближайшей к точке границы полигона».
 * Официальные документы её не уточняют, а ТЗ §2.9 обещает, что все ОКС конкурсного набора подключаемы.
 * Разбор трёх прочтений на конкурсном наборе — AUDIT/TERMINAL_2_5_10_INDEPENDENT_PROOF.md.
 * Задаётся системным свойством {@code heatnet.terminal-policy} или переменной окружения
 * {@code HEATNET_TERMINAL_POLICY}: literal | exterior | relaxed (по умолчанию literal).
 */
public enum TerminalPolicy {
    /** Дословно: ближайшая точка всей границы части с целью, включая дворы-«дырки»; только строгий луч. */
    LITERAL,
    /** Ближайшая точка внешнего контура части с целью; только строгий луч. */
    EXTERIOR,
    /** EXTERIOR, а если строгий выход невозможен по входным данным — ближайшая точка внешнего контура
     *  со свободным прямым выходом (DECISIONS №27). В diag_relaxed_final_approach перечисляются все цели, чей
     *  финальный участок не проходит через глобально ближайшую точку границы (включая стены дворов). */
    RELAXED;

    public static TerminalPolicy current() {
        String v = System.getProperty("heatnet.terminal-policy");
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv("HEATNET_TERMINAL_POLICY");
        }
        if (v == null || v.trim().isEmpty()) {
            return LITERAL;
        }
        String code = v.trim().toUpperCase(Locale.ROOT);
        // синонимы из инструмента аудита: strict = literal, any = relaxed
        if (code.equals("STRICT")) {
            return LITERAL;
        }
        if (code.equals("ANY")) {
            return RELAXED;
        }
        return valueOf(code);
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
