package io.casehub.ops.iot;

import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.Temperature.TemperatureUnit;
import io.casehub.iot.api.ThermostatMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityNormalizerTest {

    @Test
    void temperature_convertsToMapWithBigDecimal() {
        var caps = Map.<String, Object>of("targetTemperature",
            new Temperature(new BigDecimal("22"), TemperatureUnit.CELSIUS));
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("targetTemperature"))
            .isEqualTo(Map.of("value", new BigDecimal("22"), "unit", "CELSIUS"));
    }

    @Test
    void enum_convertsToString() {
        var caps = Map.<String, Object>of("mode", ThermostatMode.HEAT);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("mode")).isEqualTo("HEAT");
    }

    @Test
    void integer_convertsToBigDecimal() {
        var caps = Map.<String, Object>of("brightness", 80);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("brightness")).isEqualTo(new BigDecimal("80"));
    }

    @Test
    void double_convertsToBigDecimal() {
        var caps = Map.<String, Object>of("threshold", 22.5);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("threshold")).isInstanceOf(BigDecimal.class);
    }

    @Test
    void boolean_passesThrough() {
        var caps = Map.<String, Object>of("isOn", true);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("isOn")).isEqualTo(true);
    }

    @Test
    void stripTrailingZeros_scaleEquality() {
        var caps1 = Map.<String, Object>of("value", new BigDecimal("22.0"));
        var caps2 = Map.<String, Object>of("value", new BigDecimal("22"));
        assertThat(CapabilityNormalizer.normalize(caps1))
            .isEqualTo(CapabilityNormalizer.normalize(caps2));
    }

    @Test
    void recursesIntoNestedMaps() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("value", 22);
        inner.put("unit", "CELSIUS");
        var caps = Map.<String, Object>of("targetTemperature", inner);
        var result = CapabilityNormalizer.normalize(caps);
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) result.get("targetTemperature");
        assertThat(nested.get("value")).isEqualTo(new BigDecimal("22"));
        assertThat(nested.get("unit")).isEqualTo("CELSIUS");
    }

    @Test
    void nestedMapWithMixedTypes() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("value", 22.50);
        inner.put("label", "temp");
        inner.put("active", true);
        var caps = Map.<String, Object>of("sensor", inner);
        var result = CapabilityNormalizer.normalize(caps);
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) result.get("sensor");
        assertThat(nested.get("value")).isInstanceOf(BigDecimal.class);
        assertThat(nested.get("label")).isEqualTo("temp");
        assertThat(nested.get("active")).isEqualTo(true);
    }

    @Test
    void nullValues_filteredOut() {
        var caps = new LinkedHashMap<String, Object>();
        caps.put("isOn", true);
        caps.put("brightness", 50);
        caps.put("colorTemp", null);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result).containsKey("isOn");
        assertThat(result).containsKey("brightness");
        assertThat(result).doesNotContainKey("colorTemp");
    }
}
