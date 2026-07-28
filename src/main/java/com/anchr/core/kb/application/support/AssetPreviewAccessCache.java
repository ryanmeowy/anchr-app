package com.anchr.core.kb.application.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived per-token cache for signed whole-document preview URLs.
 */
@Component
public class AssetPreviewAccessCache {

    private static final long CACHE_SAFETY_WINDOW_MILLIS = 30_000L;
    private static final String CACHE_KEY_PREFIX = "preview:asset:";

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Optional<AssetPreviewAccess> find(String assetId, String accessTokenHash) {
        String key = buildKey(assetId, accessTokenHash);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (entry.cacheUntil() <= now || entry.access().expiresAt() == null
                || entry.access().expiresAt() <= now) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.access());
    }

    public void save(String assetId, String accessTokenHash, AssetPreviewAccess access) {
        if (access == null || !StringUtils.hasText(access.previewUrl())
                || access.expiresAt() == null || access.expiresAt() <= System.currentTimeMillis()) {
            return;
        }
        long cacheUntil = access.expiresAt() - CACHE_SAFETY_WINDOW_MILLIS;
        if (cacheUntil <= System.currentTimeMillis()) {
            return;
        }
        cache.put(buildKey(assetId, accessTokenHash), new CacheEntry(access, cacheUntil));
    }

    private String buildKey(String assetId, String accessTokenHash) {
        if (!StringUtils.hasText(assetId) || !StringUtils.hasText(accessTokenHash)) {
            throw new IllegalArgumentException("assetId and accessTokenHash cannot be blank.");
        }
        return CACHE_KEY_PREFIX + assetId.trim() + ":token:" + accessTokenHash.trim();
    }

    public record AssetPreviewAccess(String previewUrl, Long expiresAt) {
    }

    private record CacheEntry(AssetPreviewAccess access, long cacheUntil) {
    }
}
