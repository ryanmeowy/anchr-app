package com.smart.vision.core.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.vision.core.auth.RequireAuth;
import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import static com.smart.vision.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;

/**
 * Authentication Interceptor, used to verify whether a request has upload permissions.
 * This interceptor checks interface methods annotated with @RequireAuth and validates
 * if the X-Access-Token in the request header matches the configured upload token.
 *
 * @author Ryan
 * @since 2025/12/17
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;

        if (hm.getMethodAnnotation(RequireAuth.class) != null) {
            String clientToken = request.getHeader("X-Access-Token");
            String serverToken = redisTemplate.opsForValue().get(TOKEN_CACHE_PREFIX);
            if (serverToken == null || !serverToken.equals(clientToken)) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                Result<Void> result = Result.error(ApiError.AUTH_TOKEN_INVALID);
                try {
                    response.getWriter().write(objectMapper.writeValueAsString(result));
                } catch (Exception ex) {
                    log.warn("Failed to serialize auth rejection payload, fallback to minimal json", ex);
                    response.getWriter().write("{\"code\": 401, \"message\": \"The token is invalid or expired, please contact the administrator to refresh it\"}");
                }
                return false;
            }
        }
        return true;
    }
}
