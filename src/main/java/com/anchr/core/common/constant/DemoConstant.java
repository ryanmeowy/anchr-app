package com.anchr.core.common.constant;

/**
 * Redis keys and fixed values owned by the demo deployment.
 *
 * <p>Kept apart from {@link CacheConstant} so the demo footprint stays in dedicated files: the
 * only shared files this branch touches are the interceptor, the error dictionary and the
 * controllers that carry {@code @DemoQuota}.
 */
public final class DemoConstant {

    /** Token value prefix; reuses the application token key so validation code is unchanged. */
    public static final String TOKEN_CACHE_PREFIX = CacheConstant.TOKEN_CACHE_PREFIX;

    /** Per-token operation counter. TTL must outlive the token itself. */
    public static final String QUOTA_TOKEN_PREFIX = "demo:quota:token:";

    /** Daily operation counter shared by every visitor. */
    public static final String QUOTA_DAILY_OPS_PREFIX = "demo:quota:daily:ops:";

    /** Daily issued-token counter. */
    public static final String QUOTA_DAILY_ISSUED_PREFIX = "demo:quota:daily:issued:";

    /** Browser cookie id to issued token mapping, used for idempotent issuing. */
    public static final String ISSUE_TOKEN_PREFIX = "demo:issue:token:";

    /** Per-browser issue cooldown marker. */
    public static final String ISSUE_COOLDOWN_PREFIX = "demo:issue:cooldown:";

    /** Kill switch flag; {@code "1"} means the demo refuses billable traffic. */
    public static final String KILL_SWITCH_KEY = "demo:killswitch";

    /** HttpOnly cookie carrying the browser identity used for idempotent issuing. */
    public static final String BROWSER_COOKIE = "anchr_demo_id";

    /**
     * Token counters live one hour longer than the token so a still-valid token never sees a
     * reset counter.
     */
    public static final long TOKEN_COUNTER_TTL_EXTRA_HOURS = 1L;

    private DemoConstant() {
    }
}
