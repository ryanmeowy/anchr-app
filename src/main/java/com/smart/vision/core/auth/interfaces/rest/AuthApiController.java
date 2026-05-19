
package com.smart.vision.core.auth.interfaces.rest;

import com.smart.vision.core.auth.application.OssService;
import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.auth.RequireAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import static com.smart.vision.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthApiController {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.security.admin-secret}")
    private String adminSecret;

    private final OssService ossService;

    private final SecureRandom secureRandom = new SecureRandom();

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    /**
     * Remote refresh upload token interface
     */
    @GetMapping("/refresh-token")
    public Result<String> refreshToken(@RequestHeader("X-Admin-Secret") String secret,
                                       @RequestParam(required = false) String code) {
        if (!StringUtils.hasText(adminSecret)) {
            log.error("Admin secret is not configured");
            return Result.error(ApiError.AUTH_ADMIN_SECRET_MISSING);
        }
        if (!constantTimeEquals(adminSecret, secret)) {
            return Result.error(ApiError.FORBIDDEN);
        }

        String newToken;
        if (code != null && !code.isBlank()) {
            newToken = code;
        } else {
            int tokenInt = secureRandom.nextInt(900_000) + 100_000;
            newToken = String.valueOf(tokenInt);
        }
        try {
            redisTemplate.opsForValue().set(TOKEN_CACHE_PREFIX, newToken, 12, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to store token in redis", e);
            return Result.error(ApiError.INTERNAL_ERROR);
        }
        return Result.success(newToken);
    }

    @GetMapping("/clean-token")
    public Result<Void> cleanToken(@RequestHeader("X-Admin-Secret") String secret) {
        if (!StringUtils.hasText(adminSecret)) {
            log.error("Admin secret is not configured");
            return Result.error(ApiError.AUTH_ADMIN_SECRET_MISSING);
        }
        if (!constantTimeEquals(adminSecret, secret)) {
            return Result.error(ApiError.FORBIDDEN);
        }
        try {
            redisTemplate.delete(TOKEN_CACHE_PREFIX);
        } catch (Exception e) {
            log.warn("Failed to delete token in redis", e);
            return Result.error(ApiError.INTERNAL_ERROR);
        }
        return Result.success();
    }

    @RequireAuth
    @GetMapping("/sts")
    public Result<String> getStsToken() {
        try {
            return Result.success(ossService.fetchStsToken());
        } catch (BusinessException e) {
            log.warn("Upload credential issue denied: {}", e.getMessage());
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error fetching STS token", e);
            return Result.error(ApiError.AUTH_STS_FETCH_FAILED);
        }
    }
}
