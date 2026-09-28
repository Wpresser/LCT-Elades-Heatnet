package ru.lct.heatnet.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Диагностика входных данных и расчёта: счётчики и предупреждения (с ограничением объёма). */
public final class Diagnostics {

    private static final int MAX_MESSAGES = 200;

    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private long warningsTotal;

    public void count(String key) {
        add(key, 1);
    }

    public void add(String key, long delta) {
        counters.merge(key, delta, Long::sum);
    }

    public void set(String key, long value) {
        counters.put(key, value);
    }

    public long get(String key) {
        return counters.getOrDefault(key, 0L);
    }

    public void warn(String message) {
        warningsTotal++;
        if (warnings.size() < MAX_MESSAGES) {
            warnings.add(message);
        }
    }

    public Map<String, Long> counters() {
        return Collections.unmodifiableMap(counters);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public long warningsTotal() {
        return warningsTotal;
    }
}
