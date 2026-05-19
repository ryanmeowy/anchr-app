package com.smart.vision.core.ingestion.infrastructure.persistence.redis;

import com.smart.vision.core.ingestion.domain.model.ImageHashStatus;
import com.smart.vision.core.ingestion.domain.repository.ImageHashStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.smart.vision.core.common.constant.CacheConstant.HASH_INDEX_CACHE_PREFIX;
import static com.smart.vision.core.common.constant.CommonConstant.PROFILE_KEY_NAME;

@Repository
@RequiredArgsConstructor
public class RedisImageHashStateRepository implements ImageHashStateRepository {

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquireProcessing(String fileHash, long ttl, TimeUnit unit) {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                cacheKey(fileHash),
                ImageHashStatus.PROCESSING.value(),
                ttl,
                unit
        );
        return Boolean.TRUE.equals(locked);
    }

    @Override
    public Optional<ImageHashStatus> findStatus(String fileHash) {
        return ImageHashStatus.fromValue(redisTemplate.opsForValue().get(cacheKey(fileHash)));
    }

    @Override
    public void markStatus(String fileHash, ImageHashStatus status, long ttl, TimeUnit unit) {
        redisTemplate.opsForValue().set(cacheKey(fileHash), status.value(), ttl, unit);
    }

    @Override
    public void delete(String fileHash) {
        redisTemplate.delete(cacheKey(fileHash));
    }

    private String cacheKey(String fileHash) {
        return String.format("%s%s:%s", HASH_INDEX_CACHE_PREFIX, System.getenv(PROFILE_KEY_NAME), fileHash);
    }
}
