package com.anchr.core.auth.application.impl;

import com.anchr.core.auth.application.model.DemoTokenIssue;
import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.constant.DemoConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Issues demo tokens.
 *
 * <p>Two counters guard issuing: the per-browser cooldown keeps a double click from burning a
 * token, and the daily issued count keeps the remaining slots available for real visitors. The
 * browser cookie is only an identity for idempotency; it deliberately does not carry the token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemoTokenService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DemoProperties demoProperties;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param browserCookie previously presented browser cookie, may be null
     * @return the issue result plus the cookie value the caller must set
     */
    public DemoTokenResult issue(String browserCookie) {
        String cookie = StringUtils.hasText(browserCookie) ? browserCookie.trim() : newCookieId();

        DemoTokenIssue existing = findIssued(cookie);
        if (existing != null) {
            return new DemoTokenResult(existing, cookie, false);
        }
        if (onCooldown(cookie)) {
            throw new BusinessException(ApiError.DEMO_ISSUE_TOO_FREQUENT);
        }
        long issuedToday = incrementIssuedToday();
        if (issuedToday > demoProperties.dailyTokenLimit()) {
            decrementIssuedToday();
            throw new BusinessException(ApiError.DEMO_DAILY_LIMIT_REACHED);
        }

        DemoTokenIssue issue = createToken(cookie);
        startCooldown(cookie);
        log.info("Demo token issued, userId={}, issuedToday={}", issue.userId(), issuedToday);
        return new DemoTokenResult(issue, cookie, true);
    }

    public String newCookieId() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private DemoTokenIssue createToken(String browserCookie) {
        byte[] tokenBytes = new byte[24];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String userId = "demo_" + shortId();
        long expiresAt = System.currentTimeMillis()
                + Duration.ofHours(demoProperties.tokenTtlHours()).toMillis();

        Map<String, Object> tokenData = new LinkedHashMap<>();
        tokenData.put("role", "USER");
        tokenData.put("userId", userId);
        tokenData.put("createdAt", System.currentTimeMillis());
        tokenData.put("demo", true);
        try {
            String json = objectMapper.writeValueAsString(tokenData);
            redisTemplate.opsForValue().set(DemoConstant.TOKEN_CACHE_PREFIX + token, json,
                    demoProperties.tokenTtlHours(), TimeUnit.HOURS);
            redisTemplate.opsForValue().set(DemoConstant.ISSUE_TOKEN_PREFIX + browserCookie,
                    token, demoProperties.tokenTtlHours(), TimeUnit.HOURS);
        } catch (Exception exception) {
            log.error("Failed to store demo token", exception);
            throw new BusinessException(ApiError.INTERNAL_ERROR);
        }
        return new DemoTokenIssue(token, userId, expiresAt);
    }

    private DemoTokenIssue findIssued(String browserCookie) {
        try {
            String token = redisTemplate.opsForValue()
                    .get(DemoConstant.ISSUE_TOKEN_PREFIX + browserCookie);
            if (!StringUtils.hasText(token)) {
                return null;
            }
            String tokenJson = redisTemplate.opsForValue()
                    .get(DemoConstant.TOKEN_CACHE_PREFIX + token);
            if (!StringUtils.hasText(tokenJson)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(tokenJson, Map.class);
            Object userId = data.get("userId");
            Object createdAt = data.get("createdAt");
            long expiresAt = createdAt instanceof Number number
                    ? number.longValue() + Duration.ofHours(demoProperties.tokenTtlHours()).toMillis()
                    : System.currentTimeMillis();
            return new DemoTokenIssue(token, String.valueOf(userId), expiresAt);
        } catch (Exception exception) {
            log.warn("Failed to read issued demo token: {}", exception.getMessage());
            return null;
        }
    }

    private boolean onCooldown(String browserCookie) {
        try {
            return "1".equals(redisTemplate.opsForValue()
                    .get(DemoConstant.ISSUE_COOLDOWN_PREFIX + browserCookie));
        } catch (RuntimeException exception) {
            log.warn("Failed to read demo issue cooldown: {}", exception.getMessage());
            return false;
        }
    }

    private void startCooldown(String browserCookie) {
        try {
            redisTemplate.opsForValue().set(DemoConstant.ISSUE_COOLDOWN_PREFIX + browserCookie,
                    "1", demoProperties.issueCooldownMinutes(), TimeUnit.MINUTES);
        } catch (RuntimeException exception) {
            log.warn("Failed to set demo issue cooldown: {}", exception.getMessage());
        }
    }

    private long incrementIssuedToday() {
        String key = DemoConstant.QUOTA_DAILY_ISSUED_PREFIX + LocalDate.now();
        Long value = redisTemplate.opsForValue().increment(key);
        long issued = value == null ? 1L : value;
        if (issued == 1L) {
            redisTemplate.expire(key, Duration.between(
                    java.time.LocalDateTime.now(),
                    LocalDate.now().plusDays(1).atTime(0, 5)));
        }
        return issued;
    }

    private void decrementIssuedToday() {
        try {
            redisTemplate.opsForValue().decrement(
                    DemoConstant.QUOTA_DAILY_ISSUED_PREFIX + LocalDate.now());
        } catch (RuntimeException exception) {
            log.warn("Failed to roll back demo issued counter: {}", exception.getMessage());
        }
    }

    private String shortId() {
        byte[] bytes = new byte[6];
        secureRandom.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** Issue outcome plus the cookie the response must send back. */
    public record DemoTokenResult(DemoTokenIssue issue, String browserCookie, boolean created) {
    }
}
