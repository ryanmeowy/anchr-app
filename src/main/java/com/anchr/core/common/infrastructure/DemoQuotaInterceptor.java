package com.anchr.core.common.infrastructure;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.constant.DemoConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.ApiErrorResponseWriter;
import com.anchr.core.common.exception.UploadCleanupPolicy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * Charges one demo credit for every handler annotated with {@link DemoQuota}.
 *
 * <p>Registered after {@link AccessTokenInterceptor}, so the caller's token hash and role are
 * already in {@link UserContextHolder}. Handlers without the annotation never touch Redis.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoQuotaInterceptor implements AsyncHandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final DemoProperties demoProperties;
    private final DemoKillSwitch killSwitch;
    private final ApiErrorResponseWriter errorResponseWriter;
    private final UploadCleanupPolicy uploadCleanupPolicy;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        if (!demoProperties.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        // An SSE handler is dispatched twice: once for the request and again when the async
        // response completes. Only the first dispatch is a call the visitor made, so charging on
        // anything but REQUEST would bill a single question twice.
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        if (AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), DemoQuota.class) == null) {
            return true;
        }
        if (killSwitch.isOn()) {
            reject(response, handler, ApiError.DEMO_DISABLED);
            return false;
        }
        RequestUserContext context = UserContextHolder.get();
        String tokenHash = context.accessTokenHash();
        if (tokenHash == null || tokenHash.isBlank()) {
            // Reached only for anonymous handlers; there is no counter to charge.
            return true;
        }
        if (isOwner(context)) {
            // The owner's own token must not spend the shared visitor budget, otherwise testing
            // the demo silently consumes the credits real visitors need.
            return true;
        }

        long tokenUsed = incrementTokenCounter(tokenHash);
        if (tokenUsed > demoProperties.tokenQuota()) {
            decrementTokenCounter(tokenHash);
            log.info("Demo token quota exhausted, tokenHash={}", mask(tokenHash));
            reject(response, handler, ApiError.DEMO_QUOTA_EXHAUSTED);
            return false;
        }

        long dailyUsed = incrementDailyCounter();
        if (dailyUsed > demoProperties.dailyOperationLimit()) {
            decrementTokenCounter(tokenHash);
            decrementDailyCounter();
            log.warn("Demo daily operation limit reached, used={}", dailyUsed);
            reject(response, handler, ApiError.DEMO_DAILY_LIMIT_REACHED);
            return false;
        }
        return true;
    }

    /**
     * ADMIN tokens are the owner's own credentials, never visitor tokens: issuance only ever
     * hands out USER tokens and stamps them with {@code demo: true}.
     */
    private boolean isOwner(RequestUserContext context) {
        return "ADMIN".equalsIgnoreCase(context.role());
    }

    // shortcut: counters are not atomic (increment, compare, decrement on failure), so a
    // concurrent burst can let one or two extra calls through. That is within the demo budget;
    // move to a Lua script if the numbers ever need to be exact.
    private long incrementTokenCounter(String tokenHash) {
        String key = DemoConstant.QUOTA_TOKEN_PREFIX + tokenHash;
        Long value = redisTemplate.opsForValue().increment(key);
        long used = value == null ? 1L : value;
        if (used == 1L) {
            redisTemplate.expire(key, tokenCounterTtl());
        }
        return used;
    }

    private void decrementTokenCounter(String tokenHash) {
        try {
            redisTemplate.opsForValue().decrement(DemoConstant.QUOTA_TOKEN_PREFIX + tokenHash);
        } catch (RuntimeException exception) {
            log.warn("Failed to roll back demo token counter: {}", exception.getMessage());
        }
    }

    private long incrementDailyCounter() {
        String key = dailyOpsKey();
        Long value = redisTemplate.opsForValue().increment(key);
        long used = value == null ? 1L : value;
        if (used == 1L) {
            redisTemplate.expire(key, untilNextDay());
        }
        return used;
    }

    private void decrementDailyCounter() {
        try {
            redisTemplate.opsForValue().decrement(dailyOpsKey());
        } catch (RuntimeException exception) {
            log.warn("Failed to roll back demo daily counter: {}", exception.getMessage());
        }
    }

    private Duration tokenCounterTtl() {
        return Duration.ofHours(demoProperties.tokenTtlHours()
                + DemoConstant.TOKEN_COUNTER_TTL_EXTRA_HOURS);
    }

    private String dailyOpsKey() {
        return DemoConstant.QUOTA_DAILY_OPS_PREFIX + LocalDate.now();
    }

    static Duration untilNextDay() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime next = now.toLocalDate().plusDays(1).atTime(0, 5);
        return Duration.between(now, next);
    }

    private void reject(HttpServletResponse response, Object handler, ApiError error) {
        HttpStatus status = HttpStatus.resolve(error.getCode());
        errorResponseWriter.write(response,
                status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status,
                error, error.getMessage(), UUID.randomUUID().toString(),
                uploadCleanupPolicy.forPreControllerRejection(handler));
    }

    private String mask(String tokenHash) {
        return tokenHash.length() <= 8 ? tokenHash : tokenHash.substring(0, 8) + "…";
    }
}
