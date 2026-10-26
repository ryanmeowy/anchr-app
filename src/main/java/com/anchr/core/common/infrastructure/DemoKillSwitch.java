package com.anchr.core.common.infrastructure;

import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.constant.DemoConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Instant demo kill switch backed by Redis.
 *
 * <p>The state is cached in-process for {@link #CACHE_MILLIS} so a billable request does not pay
 * for a Redis round trip every time. The trade-off is deliberate: a flip becomes effective within
 * a few seconds, which is fast enough to stop a runaway demo and cheap enough to sit in front of
 * every billed call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoKillSwitch {

    /** Upper bound on how long a flip stays invisible to traffic. */
    static final long CACHE_MILLIS = 5_000L;

    private final StringRedisTemplate redisTemplate;
    private final DemoProperties demoProperties;

    private volatile boolean cachedOn = false;
    private volatile long cachedUntil = 0L;

    public boolean isOn() {
        if (!demoProperties.enabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < cachedUntil) {
            return cachedOn;
        }
        try {
            cachedOn = "1".equals(redisTemplate.opsForValue().get(DemoConstant.KILL_SWITCH_KEY));
        } catch (RuntimeException exception) {
            // Fail open on purpose: the quota counters live in the same Redis, so they are not
            // enforceable either. Refusing traffic here would turn one outage into two.
            cachedOn = false;
            log.warn("Demo kill switch unreadable, treating as OFF: {}", exception.getMessage());
        }
        cachedUntil = now + CACHE_MILLIS;
        return cachedOn;
    }

    public void set(boolean on) {
        if (on) {
            redisTemplate.opsForValue().set(DemoConstant.KILL_SWITCH_KEY, "1");
        } else {
            redisTemplate.delete(DemoConstant.KILL_SWITCH_KEY);
        }
        cachedOn = on;
        cachedUntil = System.currentTimeMillis() + CACHE_MILLIS;
        log.warn("Demo kill switch set to {}", on ? "ON" : "OFF");
    }
}
