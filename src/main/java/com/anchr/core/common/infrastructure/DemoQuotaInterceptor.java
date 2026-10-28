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

    /**
     * Request attribute holding the token hash charged by this request, so {@link #afterCompletion}
     * can refund it when the handler turns out to have failed.
     */
    private static final String CHARGED_TOKEN_ATTR = "demo.quota.chargedTokenHash";

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
        // Reached only after both counters were incremented: remember that so a failed handler
        // can be refunded. Set here rather than above, otherwise a rejected request would refund
        // a counter that was never charged for this call.
        request.setAttribute(CHARGED_TOKEN_ATTR, tokenHash);
        return true;
    }

    /**
     * Refunds the credit when the request itself failed.
     *
     * <p>A charge happens in {@code preHandle}, before the handler runs, so a malformed request or
     * a downstream error used to cost the visitor a credit for work that never happened. The
     * response status is only known here.
     *
     * <p>SSE handlers are dispatched asynchronously; this runs once the async exchange completes,
     * which is exactly once per accepted stream.
     */
    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        Object charged = request.getAttribute(CHARGED_TOKEN_ATTR);
        if (!(charged instanceof String tokenHash) || tokenHash.isBlank()) {
            return;
        }
        request.removeAttribute(CHARGED_TOKEN_ATTR);
        boolean failed = ex != null || response.getStatus() >= 400;
        if (!failed) {
            return;
        }
        decrementTokenCounter(tokenHash);
        decrementDailyCounter();
        log.info("Demo quota refunded after a failed request, tokenHash={}, status={}",
                mask(tokenHash), response.getStatus());
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
        // Same reasoning as the daily counter: never rely on `used == 1L` to set the expiry,
        // because a rollback can leave the key at 0 or below with no TTL.
        redisTemplate.expire(key, tokenCounterTtl());
        return value == null ? 1L : value;
    }

    private void decrementTokenCounter(String tokenHash) {
        try {
            String key = DemoConstant.QUOTA_TOKEN_PREFIX + tokenHash;
            Long value = redisTemplate.opsForValue().decrement(key);
            if (value != null && value <= 0) {
                redisTemplate.delete(key);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to roll back demo token counter: {}", exception.getMessage());
        }
    }

    private long incrementDailyCounter() {
        String key = dailyOpsKey();
        Long value = redisTemplate.opsForValue().increment(key);
        // Always set the expiry rather than only on the first increment. A rollback can decrement
        // a key that does not exist, which Redis creates with value -1 and no TTL; the next
        // increment then returns 0, `used == 1L` never holds again, and the counter would live
        // forever with its value permanently offset.
        redisTemplate.expire(key, untilNextDay());
        return value == null ? 1L : value;
    }

    private void decrementDailyCounter() {
        try {
            String key = dailyOpsKey();
            Long value = redisTemplate.opsForValue().decrement(key);
            if (value != null && value <= 0) {
                // Rolled back past zero: the key only ever held this failed attempt, so drop it
                // instead of leaving a negative counter behind.
                redisTemplate.delete(key);
            }
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
