package com.anchr.core.settings.infrastructure.cache;

import com.anchr.core.settings.domain.model.RuntimeConfigType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigCacheTest {

    @Test
    void shouldReturnStoredValueFromACompleteTypeCache() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(
                "anchr:settings:runtime:AGENT",
                List.of("__complete", "maxSteps")))
                .thenReturn(List.of("true", "20"));

        RuntimeConfigCache.LookupResult result =
                new RuntimeConfigCache(redisTemplate)
                        .find(RuntimeConfigType.AGENT, "maxSteps");

        assertThat(result.cacheReady()).isTrue();
        assertThat(result.value()).contains("20");
    }

    @Test
    void shouldNegativeCacheAnAbsentFieldInACompleteTypeCache() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(
                "anchr:settings:runtime:SEARCH",
                List.of("__complete", "rankConstant")))
                .thenReturn(Arrays.asList("true", null));

        RuntimeConfigCache.LookupResult result =
                new RuntimeConfigCache(redisTemplate)
                        .find(RuntimeConfigType.SEARCH, "rankConstant");

        assertThat(result.cacheReady()).isTrue();
        assertThat(result.value()).isEmpty();
    }

    @Test
    void shouldTreatIncompleteOrUnavailableRedisAsACacheMiss() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(
                eq("anchr:settings:runtime:OUTBOX"), any()))
                .thenReturn(List.of("false", "20"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        RuntimeConfigCache cache = new RuntimeConfigCache(redisTemplate);

        assertThat(cache.find(RuntimeConfigType.OUTBOX, "batchSize").cacheReady())
                .isFalse();
        assertThat(cache.find(RuntimeConfigType.OUTBOX, "batchSize").cacheReady())
                .isFalse();
    }

    @Test
    void shouldStopRetryingAsSoonAsRedisReplacementSucceeds() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .doReturn(List.of(true, true, true))
                .when(redisTemplate).execute(any(SessionCallback.class));
        RuntimeConfigCache cache = new RuntimeConfigCache(redisTemplate);

        cache.replaceAfterDatabaseCommit(
                RuntimeConfigType.SEARCH,
                Map.of("rankConstant", "60"),
                Set.of("rankConstant"));

        verify(redisTemplate, times(2)).execute(any(SessionCallback.class));
        verify(redisTemplate, never()).delete("anchr:settings:runtime:SEARCH");
    }

    @Test
    void shouldRetryThreeTimesThenClearTypeCache() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisTemplate).execute(any(SessionCallback.class));
        RuntimeConfigCache cache = new RuntimeConfigCache(redisTemplate);

        cache.replaceAfterDatabaseCommit(
                RuntimeConfigType.AGENT,
                Map.of("enabled", "true"),
                Set.of("enabled"));

        verify(redisTemplate, times(3)).execute(any(SessionCallback.class));
        verify(redisTemplate).delete("anchr:settings:runtime:AGENT");
    }

    @Test
    void shouldNotPropagateCacheClearFailureAfterDatabaseCommit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisTemplate).execute(any(SessionCallback.class));
        doThrow(new IllegalStateException("delete unavailable"))
                .when(redisTemplate).delete("anchr:settings:runtime:OUTBOX");
        RuntimeConfigCache cache = new RuntimeConfigCache(redisTemplate);

        cache.replaceAfterDatabaseCommit(
                RuntimeConfigType.OUTBOX,
                Map.of("batchSize", "20"),
                Set.of("batchSize"));

        verify(redisTemplate, times(3)).execute(any(SessionCallback.class));
        verify(redisTemplate).delete("anchr:settings:runtime:OUTBOX");
    }
}
