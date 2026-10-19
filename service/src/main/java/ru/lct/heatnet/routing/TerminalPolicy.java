package ru.lct.heatnet.routing;

import java.util.Locale;

/**
 * Трактовка §2.2 «один финальный прямой участок от ближайшей к точке границы полигона».
 * Дополнительные разъяснения от 29.09.2026, №15–17 (task/sources/Дополнительные разъяснения 29.09.2026.docx,
 * передано участнику команды, SHA-256 c0125cad…): сначала ближайшая допустимая точка внешней границы; если от неё нельзя
 * построить допустимый маршрут — другая подходящая точка внешней границы. Это режим RELAXED, он по умолчанию.
 * LITERAL (вся граница, включая дворы) оставлен для сравнения: на конкурсном наборе он даёт 14/17.
 * Задаётся системным свойством {@code heatnet.terminal-policy} или переменной окружения
 * {@code HEATNET_TERMINAL_POLICY}: literal | exterior | relaxed (по умолчанию relaxed).
 */
public enum TerminalPolicy {
    /** Дословно: ближайшая точка всей границы части с целью, включая дворы-«дырки»; только строгий луч. */
    LITERAL,
    /** Ближайшая точка внешнего контура части с целью; только строгий луч. */
    EXTERIOR,
    /** EXTERIOR, а если строгий выход невозможен по входным данным — ближайшая точка внешнего контура
     *  со свободным прямым выходом (DECISIONS №27, разъяснения 29.09 №15). В diag_relaxed_final_approach
     *  перечисляются все цели, чей финальный участок не проходит через глобально ближайшую точку границы
     *  (включая стены дворов). */
    RELAXED;

    public static TerminalPolicy current() {
        String v = System.getProperty("heatnet.terminal-policy");
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv("HEATNET_TERMINAL_POLICY");
        }
        if (v == null || v.trim().isEmpty()) {
            return RELAXED;
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
