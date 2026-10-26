package com.anchr.core.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Demo-environment settings.
 *
 * <p>These properties exist for the public demonstration deployment only. Every value carries a
 * default so a deployment that never sets them starts unchanged; the whole feature stays inert
 * while {@code enabled} is false.
 *
 * <p>Validation runs in the constructor so a misconfigured limit fails at startup instead of
 * surfacing as a silently wrong quota during a demo.
 */
@ConfigurationProperties(prefix = "app.demo")
public record DemoProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") int tokenTtlHours,
        @DefaultValue("8") int tokenQuota,
        @DefaultValue("80") int dailyOperationLimit,
        @DefaultValue("50") int dailyTokenLimit,
        @DefaultValue("10") int issueCooldownMinutes,
        @DefaultValue("1") int uploadMaxFiles,
        @DefaultValue("true") boolean turnstileEnabled,
        @DefaultValue("") String turnstileSiteKey,
        @DefaultValue("") String turnstileSecretKey
) {

    public DemoProperties {
        requirePositive(tokenTtlHours, "app.demo.token-ttl-hours");
        requirePositive(tokenQuota, "app.demo.token-quota");
        requirePositive(dailyOperationLimit, "app.demo.daily-operation-limit");
        requirePositive(dailyTokenLimit, "app.demo.daily-token-limit");
        requirePositive(uploadMaxFiles, "app.demo.upload-max-files");
        if (issueCooldownMinutes < 0) {
            throw new IllegalArgumentException(
                    "app.demo.issue-cooldown-minutes must not be negative");
        }
        if (enabled && turnstileEnabled && blank(turnstileSecretKey)) {
            throw new IllegalArgumentException(
                    "app.demo.turnstile-secret-key is required when the demo is enabled"
                            + " and Turnstile verification is on");
        }
    }

    /** Turnstile verification runs only when the demo itself is enabled. */
    public boolean turnstileVerificationActive() {
        return enabled && turnstileEnabled;
    }

    public boolean hasTurnstileSecret() {
        return !blank(turnstileSecretKey);
    }

    public String siteKey() {
        return turnstileSiteKey == null ? "" : turnstileSiteKey;
    }

    /** All defaults: the demo is disabled and every limit is the production value. */
    public static DemoProperties defaults() {
        return new DemoProperties(false, 5, 8, 80, 50, 10, 1, true, "", "");
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
