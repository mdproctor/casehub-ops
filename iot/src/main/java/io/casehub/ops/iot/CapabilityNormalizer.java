package io.casehub.ops.iot;

import io.casehub.iot.api.Temperature;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

final class CapabilityNormalizer {

    private CapabilityNormalizer() {}

    static Map<String, Object> normalize(Map<String, Object> capabilities) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : capabilities.entrySet()) {
            result.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object value) {
        if (value instanceof Temperature t) {
            return Map.of(
                "value", stripAndNormalize(t.value()),
                "unit", t.unit().name());
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Integer i) {
            return stripAndNormalize(BigDecimal.valueOf(i));
        }
        if (value instanceof Long l) {
            return stripAndNormalize(BigDecimal.valueOf(l));
        }
        if (value instanceof Double d) {
            return stripAndNormalize(BigDecimal.valueOf(d));
        }
        if (value instanceof BigDecimal bd) {
            return stripAndNormalize(bd);
        }
        if (value instanceof Map<?, ?> m) {
            return normalize((Map<String, Object>) m);
        }
        return value;
    }

    private static BigDecimal stripAndNormalize(BigDecimal bd) {
        var stripped = bd.stripTrailingZeros();
        // Avoid scientific notation by converting to plain string and back
        return new BigDecimal(stripped.toPlainString());
    }
}
