package com.anchr.core.common.util;

import com.anchr.core.settings.application.api.RuntimeConfigQueryApi;
import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RuntimeConfigUnit {

    private final RuntimeConfigQueryApi runtimeConfigQueryApi;

    public String getString(
            RuntimeConfigType type, RuntimeConfigKey key, String defaultValue) {
        return find(type, key).orElse(defaultValue);
    }

    public boolean getBoolean(
            RuntimeConfigType type, RuntimeConfigKey key, boolean defaultValue) {
        return find(type, key)
                .map(value -> parseBoolean(type, key, value))
                .orElse(defaultValue);
    }

    public int getInt(RuntimeConfigType type, RuntimeConfigKey key, int defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Integer.parseInt(value)))
                .orElse(defaultValue);
    }

    public long getLong(RuntimeConfigType type, RuntimeConfigKey key, long defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Long.parseLong(value)))
                .orElse(defaultValue);
    }

    public float getFloat(RuntimeConfigType type, RuntimeConfigKey key, float defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Float.parseFloat(value)))
                .orElse(defaultValue);
    }

    public double getDouble(RuntimeConfigType type, RuntimeConfigKey key, double defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Double.parseDouble(value)))
                .orElse(defaultValue);
    }

    public Duration getDurationSeconds(
            RuntimeConfigType type, RuntimeConfigKey key, Duration defaultValue) {
        return find(type, key)
                .map(value -> parse(
                        type, key, () -> Duration.ofSeconds(Long.parseLong(value))))
                .orElse(defaultValue);
    }

    public Duration getDurationMinutes(
            RuntimeConfigType type, RuntimeConfigKey key, Duration defaultValue) {
        return find(type, key)
                .map(value -> parse(
                        type, key, () -> Duration.ofMinutes(Long.parseLong(value))))
                .orElse(defaultValue);
    }

    public <E extends Enum<E>> E getEnum(
            RuntimeConfigType type,
            RuntimeConfigKey key,
            Class<E> enumClass,
            E defaultValue
    ) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Enum.valueOf(
                        enumClass, value.trim().toUpperCase(Locale.ROOT))))
                .orElse(defaultValue);
    }

    private Optional<String> find(RuntimeConfigType type, RuntimeConfigKey key) {
        key.requireType(type);
        return runtimeConfigQueryApi.findValue(type, key);
    }

    private boolean parseBoolean(
            RuntimeConfigType type, RuntimeConfigKey key, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw invalid(type, key, null);
    }

    private <T> T parse(
            RuntimeConfigType type, RuntimeConfigKey key, ValueParser<T> parser) {
        try {
            return parser.parse();
        } catch (RuntimeException exception) {
            throw invalid(type, key, exception);
        }
    }

    private IllegalStateException invalid(
            RuntimeConfigType type, RuntimeConfigKey key, RuntimeException cause) {
        return new IllegalStateException(
                "Invalid runtime config value, type=" + type
                        + ", key=" + key.propertyName(),
                cause);
    }

    @FunctionalInterface
    private interface ValueParser<T> {
        T parse();
    }
}
