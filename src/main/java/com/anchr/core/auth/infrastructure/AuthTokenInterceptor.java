package com.anchr.core.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.auth.RequireAuth;
import com.anchr.core.auth.application.SessionTokenService;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.model.Result;
import com.anchr.core.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.util.UUID;

import static com.anchr.core.common.constant.CacheConstant.TOKEN_CACHE_PREFIX;

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
public class AuthTokenInterceptor implements AsyncHandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionTokenService sessionTokenService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        UserContextHolder.clear();
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        try {
            if (hm.getMethodAnnotation(RequireAuth.class) != null) {
                String clientToken = request.getHeader("X-Access-Token");
                boolean accepted = sessionTokenService.resolve(clientToken)
                        .map(principal -> {
                            UserContextHolder.set(principal.toContext());
                            return true;
                        })
                        .orElseGet(() -> acceptAdminToken(clientToken));
                if (!accepted) {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    Result<Void> result = Result.error(ApiError.AUTH_TOKEN_INVALID, UUID.randomUUID().toString());
                    try {
                        response.getWriter().write(objectMapper.writeValueAsString(result));
                    } catch (Exception ex) {
                        log.warn("Failed to serialize auth rejection payload, fallback to minimal json", ex);
                        response.getWriter().write("{\"code\": 401, \"message\": \"The token is invalid or expired, please contact the administrator to refresh it\"}");
                    }
                    return false;
                }
            }
            if (UserContextHolder.get() == null) {
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

    private boolean acceptAdminToken(String clientToken) {
        String serverToken = redisTemplate.opsForValue().get(TOKEN_CACHE_PREFIX);
        if (serverToken == null || !serverToken.equals(clientToken)) {
            return false;
        }
        UserContextHolder.set(RequestUserContext.systemDefault());
        return true;
    }
}
