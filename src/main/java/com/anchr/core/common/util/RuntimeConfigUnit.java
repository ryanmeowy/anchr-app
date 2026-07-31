package com.anchr.core.common.util;

import com.anchr.core.settings.application.api.RuntimeConfigQueryApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RuntimeConfigUnit {

    private final RuntimeConfigQueryApi runtimeConfigQueryApi;

    public String getString(String type, String key, String defaultValue) {
        return find(type, key).orElse(defaultValue);
    }

    public boolean getBoolean(String type, String key, boolean defaultValue) {
        return find(type, key)
                .map(value -> parseBoolean(type, key, value))
                .orElse(defaultValue);
    }

    public int getInt(String type, String key, int defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Integer.parseInt(value)))
                .orElse(defaultValue);
    }

    public long getLong(String type, String key, long defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Long.parseLong(value)))
                .orElse(defaultValue);
    }

    public float getFloat(String type, String key, float defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Float.parseFloat(value)))
                .orElse(defaultValue);
    }

    public double getDouble(String type, String key, double defaultValue) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Double.parseDouble(value)))
                .orElse(defaultValue);
    }

    public Duration getDurationSeconds(
            String type, String key, Duration defaultValue) {
        return find(type, key)
                .map(value -> parse(
                        type, key, () -> Duration.ofSeconds(Long.parseLong(value))))
                .orElse(defaultValue);
    }

    public Duration getDurationMinutes(
            String type, String key, Duration defaultValue) {
        return find(type, key)
                .map(value -> parse(
                        type, key, () -> Duration.ofMinutes(Long.parseLong(value))))
                .orElse(defaultValue);
    }

    public <E extends Enum<E>> E getEnum(
            String type,
            String key,
            Class<E> enumClass,
            E defaultValue
    ) {
        return find(type, key)
                .map(value -> parse(type, key, () -> Enum.valueOf(
                        enumClass, value.trim().toUpperCase(Locale.ROOT))))
                .orElse(defaultValue);
    }

    private Optional<String> find(String type, String key) {
        return runtimeConfigQueryApi.findValue(type, key);
    }

    private boolean parseBoolean(String type, String key, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw invalid(type, key, null);
    }

    private <T> T parse(String type, String key, ValueParser<T> parser) {
        try {
            return parser.parse();
        } catch (RuntimeException exception) {
            throw invalid(type, key, exception);
        }
    }

    private IllegalStateException invalid(
            String type, String key, RuntimeException cause) {
        return new IllegalStateException(
                "Invalid runtime config value, type=" + type + ", key=" + key,
                cause);
    }

    @FunctionalInterface
    private interface ValueParser<T> {
        T parse();
    }
}
