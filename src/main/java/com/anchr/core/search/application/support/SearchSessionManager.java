package com.anchr.core.search.application.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Stores paged search snapshot in Redis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchSessionManager {

    private static final String SEARCH_PAGE_SESSION_PREFIX = "search:page:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppSearchProperties appSearchProperties;

    public SearchPageSession create(String queryFingerprint, List<SearchResultDTO> orderedResults) {
        long now = System.currentTimeMillis();
        long sessionTtlSeconds = appSearchProperties.getPage().getSessionTtlSeconds();
        long expireAt = now + TimeUnit.SECONDS.toMillis(Math.max(60, sessionTtlSeconds));
        SearchPageSession session = new SearchPageSession(
                UUID.randomUUID().toString(),
                queryFingerprint,
                expireAt,
                orderedResults == null ? List.of() : orderedResults
        );
        save(session);
        return session;
    }

    public Optional<SearchPageSession> find(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        String key = buildKey(sessionId);
        String raw = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        try {
            SearchPageSession session = objectMapper.readValue(raw, SearchPageSession.class);
            if (session.getExpireAt() <= System.currentTimeMillis()) {
                redisTemplate.delete(key);
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (Exception e) {
            log.warn("Failed to parse search page session, key={}", key, e);
            redisTemplate.delete(key);
            return Optional.empty();
        }
    }

    private void save(SearchPageSession session) {
        try {
            long sessionTtlSeconds = appSearchProperties.getPage().getSessionTtlSeconds();
            redisTemplate.opsForValue().set(
                    buildKey(session.getSessionId()),
                    objectMapper.writeValueAsString(session),
                    Math.max(60, sessionTtlSeconds),
                    TimeUnit.SECONDS
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize search page session.", e);
        }
    }

    private String buildKey(String sessionId) {
        return SEARCH_PAGE_SESSION_PREFIX + sessionId;
    }
}
