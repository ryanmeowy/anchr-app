package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.interfaces.rest.dto.TokenValidationDTO;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.integration.storage.StorageTokenIssuer;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.anchr.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final StringRedisTemplate redisTemplate;
    private final StorageConfigRepository storageConfigRepository;
    private final AesUtil aesUtil;
    private final StorageTokenIssuer storageTokenIssuer;
    private final ObjectMapper objectMapper;

    @Value("${app.security.admin-secret}")
    private String adminSecret;

    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping("/validate-token")
    public Result<TokenValidationDTO> validateToken(
            @RequestHeader(value = "X-Access-Token", required = false) String token) {
        if (!StringUtils.hasText(token)) {
            return Result.error(ApiError.AUTH_TOKEN_INVALID);
        }

        try {
            String tokenJson = redisTemplate.opsForValue().get(TOKEN_CACHE_PREFIX + token.trim());
            if (!StringUtils.hasText(tokenJson)) {
                return Result.error(ApiError.AUTH_TOKEN_INVALID);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> tokenData = objectMapper.readValue(tokenJson, Map.class);
            String role = toPublicRole(tokenData.get("role"));
            if (role == null) {
                return Result.error(ApiError.AUTH_TOKEN_INVALID);
            }
            return Result.success(TokenValidationDTO.builder()
                    .valid(true)
                    .role(role)
                    .build());
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse token data", e);
            return Result.error(ApiError.AUTH_TOKEN_INVALID);
        } catch (Exception e) {
            log.error("Failed to validate token", e);
            return Result.error(ApiError.INTERNAL_ERROR);
        }
    }

    private String toPublicRole(Object storedRole) {
        if (!(storedRole instanceof String role)) {
            return null;
        }
        role = role.trim().toUpperCase();
        if (!"ADMIN".equals(role)
                && !"USER".equals(role)
                && !"GUEST".equals(role)) {
            return null;
        }
        return role;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    @GetMapping("/refresh-token")
    public Result<String> refreshToken(@RequestHeader("X-Admin-Secret") String secret,
                                       @RequestParam(required = false) String code,
                                       @RequestParam String role) {
        if (!StringUtils.hasText(adminSecret)) {
            log.error("Admin secret is not configured");
            return Result.error(ApiError.AUTH_ADMIN_SECRET_MISSING);
        }
        if (!constantTimeEquals(adminSecret, secret)) {
            return Result.error(ApiError.FORBIDDEN);
        }
        String requestedRole = role.trim().toUpperCase();
        if (!"ADMIN".equals(requestedRole)
                && !"USER".equals(requestedRole)
                && !"GUEST".equals(requestedRole)) {
            return Result.error(ApiError.INVALID_REQUEST);
        }
        String newToken;
        if (code != null && !code.isBlank()) {
            newToken = code;
        } else {
            byte[] tokenBytes = new byte[24];
            secureRandom.nextBytes(tokenBytes);
            newToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        }
        String redisKey = TOKEN_CACHE_PREFIX + newToken;
        Map<String, Object> tokenData = new LinkedHashMap<>();
        tokenData.put("role", requestedRole);
        tokenData.put("createdAt", System.currentTimeMillis());
        try {
            String json = objectMapper.writeValueAsString(tokenData);
            redisTemplate.opsForValue().set(redisKey, json, 1, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize token data", e);
            return Result.error(ApiError.INTERNAL_ERROR);
        } catch (Exception e) {
            log.error("Failed to store token in redis", e);
            return Result.error(ApiError.INTERNAL_ERROR);
        }
        log.info("Token refreshed: role={}", requestedRole);
        return Result.success(newToken);
    }

    @GetMapping("/clean-token")
    public Result<Void> cleanToken(@RequestHeader("X-Admin-Secret") String secret,
                                   @RequestParam(required = false) String token) {
        if (!StringUtils.hasText(adminSecret)) {
            log.error("Admin secret is not configured");
            return Result.error(ApiError.AUTH_ADMIN_SECRET_MISSING);
        }
        if (!constantTimeEquals(adminSecret, secret)) {
            return Result.error(ApiError.FORBIDDEN);
        }
        try {
            if (StringUtils.hasText(token)) {
                String redisKey = TOKEN_CACHE_PREFIX + token.trim();
                Boolean deleted = redisTemplate.delete(redisKey);
                if (Boolean.TRUE.equals(deleted)) {
                    log.info("Token deleted: {}", redisKey);
                } else {
                    log.info("Token not found for deletion: {}", redisKey);
                }
            } else {
                Set<String> keys = scanTokenKeys();
                if (!keys.isEmpty()) {
                    redisTemplate.delete(keys);
                    log.info("All tokens deleted, count={}", keys.size());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete token in redis", e);
            return Result.error(ApiError.INTERNAL_ERROR);
        }
        return Result.success();
    }

    @GetMapping("/list-tokens")
    public Result<List<Map<String, Object>>> listTokens(@RequestHeader("X-Admin-Secret") String secret) {
        if (!StringUtils.hasText(adminSecret)) {
            log.error("Admin secret is not configured");
            return Result.error(ApiError.AUTH_ADMIN_SECRET_MISSING);
        }
        if (!constantTimeEquals(adminSecret, secret)) {
            return Result.error(ApiError.FORBIDDEN);
        }
        List<Map<String, Object>> tokens = new ArrayList<>();
        try {
            Set<String> keys = scanTokenKeys();
            for (String key : keys) {
                String rawToken = key.substring(TOKEN_CACHE_PREFIX.length());
                String json = redisTemplate.opsForValue().get(key);
                if (!StringUtils.hasText(json)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(json, Map.class);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tokenPreview", maskToken(rawToken));
                entry.put("role", data.getOrDefault("role", "UNKNOWN"));
                entry.put("createdAt", data.get("createdAt"));
                tokens.add(entry);
            }
        } catch (Exception e) {
            log.error("Failed to list tokens", e);
            return Result.error(ApiError.INTERNAL_ERROR);
        }
        return Result.success(tokens);
    }

    private Set<String> scanTokenKeys() {
        ScanOptions options = ScanOptions.scanOptions().match(TOKEN_CACHE_PREFIX + "*").build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            Set<String> keys = new HashSet<>();
            cursor.forEachRemaining(keys::add);
            return keys;
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return token == null ? "" : token.substring(0, Math.min(4, token.length())) + "...";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @GetMapping("/sts")
    public Result<Map<String, Object>> getStsToken() {
        StorageConfig config = storageConfigRepository.find()
                .orElseThrow(() -> new RuntimeException("Object storage is not configured."));
        try {
            String accessKey = aesUtil.decrypt(config.getAccessKeyEnc());
            String secretKey = aesUtil.decrypt(config.getSecretKeyEnc());
            return Result.success(storageTokenIssuer.issueToken(config, accessKey, secretKey));
        } catch (Exception e) {
            log.error("Failed to issue STS token", e);
            return Result.error(ApiError.AUTH_STS_FETCH_FAILED);
        }
    }
}
