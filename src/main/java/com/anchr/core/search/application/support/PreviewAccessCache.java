package com.anchr.core.search.application.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PreviewAccessCache {

    private static final long CACHE_SAFETY_WINDOW_MILLIS = 30_000L;
    private static final String CACHE_KEY_PREFIX = "preview:segment:";

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Optional<PreviewAccess> find(String segmentId, String accessTokenHash) {
        String key = buildKey(segmentId, accessTokenHash);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (entry.cacheUntil() <= now || entry.access().expiresAt() <= now) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.access());
    }

    public void save(String segmentId, String accessTokenHash, PreviewAccess access) {
        if (access == null
                || !StringUtils.hasText(access.url())
                || access.expiresAt() == null
                || access.expiresAt() <= System.currentTimeMillis()) {
            return;
        }
        long cacheUntil = access.expiresAt() - CACHE_SAFETY_WINDOW_MILLIS;
        if (cacheUntil <= System.currentTimeMillis()) {
            return;
        }
        cache.put(buildKey(segmentId, accessTokenHash), new CacheEntry(access, cacheUntil));
    }

    public void evict(String segmentId, String accessTokenHash) {
        if (!StringUtils.hasText(segmentId) || !StringUtils.hasText(accessTokenHash)) {
            return;
        }
        cache.remove(buildKey(segmentId, accessTokenHash));
    }

    private String buildKey(String segmentId, String accessTokenHash) {
        if (!StringUtils.hasText(accessTokenHash)) {
            throw new IllegalArgumentException("accessTokenHash cannot be blank.");
        }
        return CACHE_KEY_PREFIX + segmentId + ":token:" + accessTokenHash;
    }

    public record PreviewAccess(String url, Long expiresAt) {
    }

    private record CacheEntry(PreviewAccess access, long cacheUntil) {
    }
}
