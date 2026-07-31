package com.anchr.core.settings.infrastructure.cache;

import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigCache {

    private static final String KEY_PREFIX = "anchr:settings:runtime:";
    private static final String COMPLETE_FIELD = "__complete";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int WRITE_ATTEMPTS = 3;

    private final StringRedisTemplate redisTemplate;

    public LookupResult find(RuntimeConfigType type, RuntimeConfigKey configKey) {
        configKey.requireType(type);
        String field = configKey.propertyName();
        try {
            List<Object> values = redisTemplate.opsForHash().multiGet(
                    key(type), List.of(COMPLETE_FIELD, field));
            if (values == null
                    || values.size() != 2
                    || !"true".equals(values.getFirst())) {
                return LookupResult.cacheMiss();
            }
            Object value = values.get(1);
            return value instanceof String text && !text.isBlank()
                    ? LookupResult.ready(text)
                    : LookupResult.ready(null);
        } catch (RuntimeException exception) {
            log.warn("runtime config cache read failed, type={}, key={}, message={}",
                    type, field, exception.getMessage());
            return LookupResult.cacheMiss();
        }
    }

    public Optional<Map<RuntimeConfigKey, String>> getStored(RuntimeConfigType type) {
        try {
            Map<Object, Object> cached = redisTemplate.opsForHash().entries(key(type));
            if (!"true".equals(cached.get(COMPLETE_FIELD))) {
                return Optional.empty();
            }
            LinkedHashMap<RuntimeConfigKey, String> values = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : cached.entrySet()) {
                if (COMPLETE_FIELD.equals(entry.getKey())) {
                    continue;
                }
                if (entry.getKey() instanceof String field
                        && entry.getValue() instanceof String text
                        && !text.isBlank()) {
                    values.put(RuntimeConfigKey.parse(type, field), text);
                }
            }
            return Optional.of(Map.copyOf(values));
        } catch (RuntimeException exception) {
            log.warn("runtime config cache read failed, type={}, message={}",
                    type, exception.getMessage());
            return Optional.empty();
        }
    }

    public void populate(
            RuntimeConfigType type, Map<RuntimeConfigKey, String> values) {
        try {
            replace(type, values);
        } catch (RuntimeException exception) {
            log.warn("runtime config cache population failed, type={}, message={}",
                    type, exception.getMessage());
        }
    }

    public void replaceAfterDatabaseCommit(
            RuntimeConfigType type,
            Map<RuntimeConfigKey, String> values,
            Set<RuntimeConfigKey> updatedKeys
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                replace(type, values);
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn(
                        "runtime config cache update failed, type={}, attempt={}, updatedKeys={}, message={}",
                        type, attempt, updatedKeys, exception.getMessage());
            }
        }
        try {
            redisTemplate.delete(key(type));
            log.warn(
                    "runtime config cache cleared after update retries were exhausted, type={}, updatedKeys={}",
                    type, updatedKeys);
        } catch (RuntimeException deleteFailure) {
            log.error(
                    "runtime config cache clear failed after update retries were exhausted, "
                            + "type={}, updatedKeys={}, updateError={}, clearError={}",
                    type,
                    updatedKeys,
                    lastFailure == null ? "unknown" : lastFailure.getMessage(),
                    deleteFailure.getMessage(),
                    deleteFailure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void replace(
            RuntimeConfigType type, Map<RuntimeConfigKey, String> values) {
        LinkedHashMap<String, String> cacheValues = new LinkedHashMap<>();
        values.forEach((configKey, value) -> {
            configKey.requireType(type);
            cacheValues.put(configKey.propertyName(), value);
        });
        cacheValues.put(COMPLETE_FIELD, "true");
        List<Object> result = redisTemplate.execute(new SessionCallback<>() {
            @Override
            public List<Object> execute(RedisOperations operations) {
                String cacheKey = key(type);
                operations.multi();
                operations.delete(cacheKey);
                operations.opsForHash().putAll(cacheKey, cacheValues);
                operations.expire(cacheKey, TTL);
                return operations.exec();
            }
        });
        if (result == null) {
            throw new IllegalStateException("Redis transaction returned no result");
        }
    }

    private String key(RuntimeConfigType type) {
        return KEY_PREFIX + type.name();
    }

    public record LookupResult(boolean cacheReady, Optional<String> value) {

        static LookupResult cacheMiss() {
            return new LookupResult(false, Optional.empty());
        }

        static LookupResult ready(String value) {
            return new LookupResult(true, Optional.ofNullable(value));
        }
    }
}
