package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.Temperature.TemperatureUnit;

import java.math.BigDecimal;
import java.util.Map;

final class CapabilityCommandMapper {

    record CommandContext(String dispatchedBy, String correlationId) {}

    private CapabilityCommandMapper() {}

    @SuppressWarnings("unchecked")
    static DeviceCommand toCommand(String deviceId, String key, Object value,
                                    CommandContext ctx) {
        return switch (key) {
            case "isOn" -> (boolean) value
                ? DeviceCommand.turnOn(deviceId, Map.of(), ctx.dispatchedBy(), ctx.correlationId())
                : DeviceCommand.turnOff(deviceId, ctx.dispatchedBy(), ctx.correlationId());
            case "targetTemperature" -> {
                var map = (Map<String, Object>) value;
                var temp = new Temperature(
                    toBigDecimal(map.get("value")),
                    TemperatureUnit.valueOf((String) map.get("unit")));
                yield DeviceCommand.setTemperature(deviceId, temp,
                    ctx.dispatchedBy(), ctx.correlationId());
            }
            case "isLocked" -> (boolean) value
                ? DeviceCommand.lock(deviceId, ctx.dispatchedBy(), ctx.correlationId())
                : DeviceCommand.unlock(deviceId, ctx.dispatchedBy(), ctx.correlationId());
            case "position" -> DeviceCommand.setPosition(deviceId, ((Number) value).intValue(),
                ctx.dispatchedBy(), ctx.correlationId());
            case "volume" -> DeviceCommand.setVolume(deviceId, ((Number) value).intValue(),
                ctx.dispatchedBy(), ctx.correlationId());
            default -> new DeviceCommand(deviceId, "set_" + key, Map.of(key, value),
                ctx.dispatchedBy(), ctx.correlationId());
        };
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Integer i) return BigDecimal.valueOf(i);
        if (value instanceof Long l) return BigDecimal.valueOf(l);
        if (value instanceof Double d) return BigDecimal.valueOf(d);
        throw new IllegalArgumentException("Cannot convert to BigDecimal: " + value);
    }
}
