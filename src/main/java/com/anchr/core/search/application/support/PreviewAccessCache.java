package com.anchr.core.search.application.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PreviewAccessCache {

    private static final long CACHE_SAFETY_WINDOW_MILLIS = 30_000L;
    private static final String CACHE_KEY_PREFIX = "preview:object:";

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Optional<PreviewAccess> find(String objectIdentity, String accessTokenHash) {
        if (!StringUtils.hasText(objectIdentity)) return Optional.empty();
        String key = buildKey(objectIdentity, accessTokenHash);
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

    public void save(String objectIdentity, String accessTokenHash, PreviewAccess access) {
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
        cache.put(buildKey(objectIdentity, accessTokenHash), new CacheEntry(access, cacheUntil));
    }

    public void evict(String objectIdentity, String accessTokenHash) {
        if (!StringUtils.hasText(objectIdentity) || !StringUtils.hasText(accessTokenHash)) {
            return;
        }
        cache.remove(buildKey(objectIdentity, accessTokenHash));
    }

    private String buildKey(String objectIdentity, String accessTokenHash) {
        if (!StringUtils.hasText(objectIdentity) || !StringUtils.hasText(accessTokenHash)) {
            throw new IllegalArgumentException("objectIdentity and accessTokenHash cannot be blank.");
        }
        return CACHE_KEY_PREFIX + objectIdentity.trim() + ":token:" + accessTokenHash.trim();
    }

    public record PreviewAccess(String url, Long expiresAt) {
    }

    private record CacheEntry(PreviewAccess access, long cacheUntil) {
    }
}
