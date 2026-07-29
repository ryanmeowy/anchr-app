package com.anchr.core.common.infrastructure;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiErrorResponseWriter;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.UploadCleanupPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static com.anchr.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;

/**
 * Authentication Interceptor, validates X-Access-Token against the
 * admin token stored in Redis (set via refresh-token endpoint).
 *
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccessTokenInterceptor implements AsyncHandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApiErrorResponseWriter errorResponseWriter;
    private final UploadCleanupPolicy uploadCleanupPolicy;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        UserContextHolder.clear();
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        try {
            RequireAuth requireAuth = hm.getMethodAnnotation(RequireAuth.class);
            if (requireAuth != null) {
                String clientToken = request.getHeader("X-Access-Token");
                if (clientToken == null || clientToken.isBlank()) {
                    reject(response, handler, 401, ApiError.AUTH_TOKEN_INVALID);
                    return false;
                }
                String redisKey = TOKEN_CACHE_PREFIX + clientToken.trim();
                String tokenJson = redisTemplate.opsForValue().get(redisKey);
                if (tokenJson == null) {
                    reject(response, handler, 401, ApiError.AUTH_TOKEN_INVALID);
                    return false;
                }
                String role;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tokenData = objectMapper.readValue(tokenJson, Map.class);
                    role = (String) tokenData.getOrDefault("role", "");
                } catch (Exception e) {
                    log.warn("Failed to parse token JSON", e);
                    reject(response, handler, 401, ApiError.AUTH_TOKEN_INVALID);
                    return false;
                }
                String[] allowedRoles = requireAuth.roles();
                boolean roleAllowed = Arrays.asList(allowedRoles).contains(role);
                if (!roleAllowed) {
                    reject(response, handler, 403, ApiError.AUTH_ROLE_FORBIDDEN);
                    return false;
                }
                UserContextHolder.set(new RequestUserContext(
                        RequestUserContext.DEFAULT_USER_ID,
                        role,
                        tokenHash(clientToken)
                ));
            } else {
                UserContextHolder.set(RequestUserContext.systemDefault());
            }
            return true;
        } catch (Exception e) {
            UserContextHolder.clear();
            throw e;
        }
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        UserContextHolder.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        UserContextHolder.clear();
    }

    private String tokenHash(String accessToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(accessToken.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable.", e);
        }
    }

    private void reject(HttpServletResponse response, Object handler, int httpStatus, ApiError error) {
        errorResponseWriter.write(response, org.springframework.http.HttpStatus.valueOf(httpStatus), error,
                error.getMessage(), UUID.randomUUID().toString(),
                uploadCleanupPolicy.forPreControllerRejection(handler));
    }
}
