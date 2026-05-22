package com.anchr.core.search.application.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PreviewAccessCache {

    private static final long CACHE_SAFETY_WINDOW_MILLIS = 30_000L;
    private static final String CACHE_KEY_PREFIX = "preview:segment:";
    private static final String ANONYMOUS_TOKEN_HASH = "anonymous";

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Optional<PreviewAccess> find(String segmentId, String accessToken) {
        String key = buildKey(segmentId, accessToken);
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

    public void save(String segmentId, String accessToken, PreviewAccess access) {
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
        cache.put(buildKey(segmentId, accessToken), new CacheEntry(access, cacheUntil));
    }

    public void evict(String segmentId, String accessToken) {
        if (!StringUtils.hasText(segmentId)) {
            return;
        }
        cache.remove(buildKey(segmentId, accessToken));
    }

    private String buildKey(String segmentId, String accessToken) {
        return CACHE_KEY_PREFIX + segmentId + ":token:" + tokenHash(accessToken);
    }

    private String tokenHash(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return ANONYMOUS_TOKEN_HASH;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(accessToken.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable.", e);
        }
    }

    public record PreviewAccess(String url, Long expiresAt) {
    }

    private record CacheEntry(PreviewAccess access, long cacheUntil) {
    }
}
