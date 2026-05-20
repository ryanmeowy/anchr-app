package com.anchr.core.ingestion.infrastructure.persistence.redis;

import com.anchr.core.ingestion.domain.model.ImageHashStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static com.anchr.core.common.constant.CacheConstant.HASH_INDEX_CACHE_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisImageHashStateRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisImageHashStateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RedisImageHashStateRepository(redisTemplate);
    }

    @Test
    void tryAcquireProcessing_shouldSetProcessingStatusWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq(ImageHashStatus.PROCESSING.value()), eq(30L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);

        boolean acquired = repository.tryAcquireProcessing("hash-1", 30L, TimeUnit.MINUTES);

        assertThat(acquired).isTrue();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(keyCaptor.capture(), eq(ImageHashStatus.PROCESSING.value()), eq(30L), eq(TimeUnit.MINUTES));
        assertThat(keyCaptor.getValue()).startsWith(HASH_INDEX_CACHE_PREFIX);
        assertThat(keyCaptor.getValue()).endsWith(":hash-1");
    }

    @Test
    void findStatus_shouldConvertRawValueToEnum() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("SUCCESS");

        assertThat(repository.findStatus("hash-2")).contains(ImageHashStatus.SUCCESS);
    }

    @Test
    void markStatus_shouldWriteStatusValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        repository.markStatus("hash-3", ImageHashStatus.FAILED, 10L, TimeUnit.MINUTES);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), eq(ImageHashStatus.FAILED.value()), eq(10L), eq(TimeUnit.MINUTES));
        assertThat(keyCaptor.getValue()).startsWith(HASH_INDEX_CACHE_PREFIX);
        assertThat(keyCaptor.getValue()).endsWith(":hash-3");
    }

    @Test
    void delete_shouldDeleteHashKey() {
        repository.delete("hash-4");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).delete(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith(HASH_INDEX_CACHE_PREFIX);
        assertThat(keyCaptor.getValue()).endsWith(":hash-4");
    }
}
