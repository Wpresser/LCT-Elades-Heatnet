package ru.lct.heatnet.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Идентификатор объекта. Может быть строкой или числом (техприложение §1, §7);
 * тип сохраняется и при выгрузке пишется так же, как пришёл.
 */
public final class FeatureId {

    private final String text;
    private final BigDecimal number;

    private FeatureId(String text, BigDecimal number) {
        this.text = text;
        this.number = number;
    }

    public static FeatureId of(String s) {
        return new FeatureId(Objects.requireNonNull(s), null);
    }

    public static FeatureId of(long n) {
        return new FeatureId(null, BigDecimal.valueOf(n));
    }

    /** null, если узел не строка и не число. */
    public static FeatureId fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return of(node.asText());
        }
        if (node.isNumber()) {
            return new FeatureId(null, node.decimalValue().stripTrailingZeros());
        }
        return null;
    }

    public boolean isNumber() {
        return number != null;
    }

    public void write(JsonGenerator g) throws IOException {
        if (number != null) {
            if (number.scale() <= 0) {
                g.writeNumber(number.toBigIntegerExact());
            } else {
                g.writeNumber(number);
            }
        } else {
            g.writeString(text);
        }
    }

    public void writeField(JsonGenerator g, String field) throws IOException {
        g.writeFieldName(field);
        write(g);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FeatureId)) {
            return false;
        }
        FeatureId f = (FeatureId) o;
        return Objects.equals(text, f.text) && (number == null ? f.number == null
                : f.number != null && number.compareTo(f.number) == 0);
    }

    @Override
    public int hashCode() {
        return number != null ? number.stripTrailingZeros().hashCode() : text.hashCode();
    }

    @Override
    public String toString() {
        return number != null ? number.toPlainString() : text;
    }
}
