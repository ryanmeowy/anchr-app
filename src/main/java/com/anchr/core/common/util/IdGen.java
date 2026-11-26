package com.anchr.core.common.util;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.anchr.core.common.constant.CacheConstant.ID_GEN_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdGen {

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private final StringRedisTemplate stringRedisTemplate;

    // Instance-level lock for allocating IDs within this JVM; Redis INCR reserves segments across instances.
    private final Lock lock = new ReentrantLock();

    private String cacheKey;

    private long currentSegmentMaxId = -1;

    private long currentId = -1;

    private volatile boolean exhausted = false;

    private static final long ID_GEN_MIN_ID = 1_000_000_000L;

    private static final long ID_GEN_MAX_ID = 9_999_999_999L;

    private static final int ID_GEN_MAX_STEP = 100;

    private static final int ID_GEN_MIN_STEP = 1;

    private static final int ID_GEN_SEGMENT_SIZE = 1000;

    @PostConstruct
    public void init() {
        if (ID_GEN_MAX_STEP > ID_GEN_SEGMENT_SIZE) {
            throw new IllegalArgumentException("MAX_STEP MUST be <= SEGMENT_SIZE to prevent duplicate IDs");
        }
        this.cacheKey = ID_GEN_KEY;
        String currentValue = stringRedisTemplate.opsForValue().get(cacheKey);
        if (currentValue != null) {
            if (!isValidCounter(currentValue)) {
                throw new IllegalStateException(
                        "ID generator counter is invalid: " + cacheKey);
            }
            return;
        }

        // Older releases scoped the counter by spring.profiles.active. Migrate from the
        // highest valid legacy counter, not just the current profile's key: PROFILE may
        // already have changed when the new release starts for the first time, and
        // restarting from ID_GEN_MIN_ID would reissue IDs that already exist in MySQL.
        String activeLegacyKey = ID_GEN_KEY + ":" + activeProfile;
        String activeLegacyValue =
                stringRedisTemplate.opsForValue().get(activeLegacyKey);
        if (activeLegacyValue != null && !isValidCounter(activeLegacyValue)) {
            throw new IllegalStateException(
                    "Legacy ID generator counter is invalid: " + activeLegacyKey);
        }

        long highestLegacyValue = 0L;
        for (String legacyKey : scanLegacyCounterKeys()) {
            String value = legacyKey.equals(activeLegacyKey)
                    ? activeLegacyValue
                    : stringRedisTemplate.opsForValue().get(legacyKey);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!isValidCounter(value)) {
                log.warn("Ignoring invalid legacy ID generator counter [{}]", legacyKey);
                continue;
            }
            highestLegacyValue = Math.max(highestLegacyValue, Long.parseLong(value));
        }

        String initialValue = highestLegacyValue > 0L
                ? String.valueOf(highestLegacyValue)
                : String.valueOf(ID_GEN_MIN_ID);
        Boolean created = stringRedisTemplate.opsForValue()
                .setIfAbsent(cacheKey, initialValue);
        if (Boolean.TRUE.equals(created) && highestLegacyValue > 0L) {
            log.info("Migrated ID generator counter from legacy key(s) to [{}]", cacheKey);
        }
    }

    private Set<String> scanLegacyCounterKeys() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(ID_GEN_KEY + ":*")
                .count(100)
                .build();
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    private boolean isValidCounter(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= ID_GEN_MIN_ID && parsed <= ID_GEN_MAX_ID;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public String nextIdStr() {
        return String.valueOf(nextId());
    }

    public long nextId() {
        if (exhausted) {
            throw new RuntimeException("ID Space Exhausted: Global limit reached.");
        }
        int step = ThreadLocalRandom.current().nextInt(ID_GEN_MAX_STEP - ID_GEN_MIN_STEP + 1) + ID_GEN_MIN_STEP;

        lock.lock();
        try {
            if (currentId < 0 || (currentId + step) > currentSegmentMaxId) {
                loadNextSegment();
            }
            currentId += step;
            if (currentId > ID_GEN_MAX_ID) {
                throw new RuntimeException("ID Space Exhausted: Global limit reached.");
            }
            return currentId;
        } finally {
            lock.unlock();
        }
    }

    private void loadNextSegment() {
        try {
            Long newMaxId = stringRedisTemplate.opsForValue().increment(cacheKey, ID_GEN_SEGMENT_SIZE);
            if (newMaxId == null || newMaxId > ID_GEN_MAX_ID) {
                exhausted = true;
                throw new RuntimeException("ID Space Exhausted: Global limit reached.");
            }
            currentSegmentMaxId = newMaxId;
            currentId = newMaxId - ID_GEN_SEGMENT_SIZE;
            log.info("Loaded new ID segment. Range: [{}, {}]", currentId, currentSegmentMaxId);
        } catch (Exception e) {
            log.error("Failed to load ID segment from Redis", e);
            throw new RuntimeException("ID Generation Service Unavailable", e);
        }
    }
}
