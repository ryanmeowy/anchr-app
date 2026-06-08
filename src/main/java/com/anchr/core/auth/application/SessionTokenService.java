package com.anchr.core.auth.application;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed local account sessions.
 */
@Service
public class SessionTokenService {

    private static final String SESSION_PREFIX = "auth:session:";
    private static final int TOKEN_BYTES = 32;
    private static final long SESSION_TTL_HOURS = 24L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionTokenService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String create(SessionPrincipal principal) {
        String token = nextToken();
        try {
            redisTemplate.opsForValue().set(SESSION_PREFIX + token, objectMapper.writeValueAsString(principal),
                    SESSION_TTL_HOURS, TimeUnit.HOURS);
            return token;
        } catch (Exception e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to create session.", e);
        }
    }

    public Optional<SessionPrincipal> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(SESSION_PREFIX + token.trim());
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, SessionPrincipal.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(SESSION_PREFIX + token.trim());
        }
    }

    private String nextToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Value
    @Builder
    public static class SessionPrincipal {
        String userId;
        String email;
        String displayName;
        String workspaceId;
        String role;

        public RequestUserContext toContext() {
            return new RequestUserContext(workspaceId, userId, role);
        }
    }
}
