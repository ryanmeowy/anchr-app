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
        @DefaultValue("2") int tokenTtlHours,
        @DefaultValue("10") int tokenQuota,
        @DefaultValue("80") int dailyOperationLimit,
        @DefaultValue("50") int dailyTokenLimit,
        @DefaultValue("10") int issueCooldownMinutes,
        @DefaultValue("1") int uploadMaxFiles,
        @DefaultValue("true") boolean captchaEnabled,
        @DefaultValue("") String captchaSceneId,
        @DefaultValue("") String captchaPrefix,
        @DefaultValue("cn-shanghai") String captchaRegion,
        @DefaultValue("") String captchaAccessKeyId,
        @DefaultValue("") String captchaAccessKeySecret,
        @DefaultValue("") String captchaEkey,
        @DefaultValue("false") boolean captchaEncryptSceneId,
        @DefaultValue("3600") int captchaSceneIdTtlSeconds,
        @DefaultValue("false") boolean startClosed
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
        if (enabled && captchaEnabled) {
            // Both halves are required: without them the entry card cannot render a challenge,
            // and the failure would only show up in front of a visitor.
            if (blank(captchaSceneId)) {
                throw new IllegalArgumentException(
                        "app.demo.captcha.scene-id is required when the demo is enabled"
                                + " and CAPTCHA verification is on");
            }
            if (captchaEncryptSceneId && blank(captchaEkey)) {
                throw new IllegalArgumentException(
                        "app.demo.captcha.ekey is required when encryption mode is on");
            }
            if (captchaSceneIdTtlSeconds < 1 || captchaSceneIdTtlSeconds > 86_400) {
                throw new IllegalArgumentException(
                        "app.demo.captcha.scene-id-ttl-seconds must be between 1 and 86400");
            }
            if (blank(captchaAccessKeyId) || blank(captchaAccessKeySecret)) {
                throw new IllegalArgumentException(
                        "app.demo.captcha.access-key-id and access-key-secret are required"
                                + " when the demo is enabled and CAPTCHA verification is on");
            }
        }
    }

    /** CAPTCHA verification runs only when the demo itself is enabled. */
    public boolean captchaVerificationActive() {
        return enabled && captchaEnabled;
    }

    public boolean hasCaptchaCredentials() {
        return !blank(captchaAccessKeyId) && !blank(captchaAccessKeySecret) && !blank(captchaSceneId);
    }



    /**
     * What the browser receives instead of the raw scenario id.
     *
     * <p>With encryption mode on this is a short-lived AES token; otherwise the id passes through
     * unchanged. The decision stays on the server so the toggle cannot drift from the console.
     */
    public String encryptedSceneIdForClient() {
        String raw = sceneId();
        if (!captchaVerificationActive() || !captchaEncryptSceneId || raw.isBlank()) {
            return null;
        }
        return com.anchr.core.integration.security.CaptchaSceneIdEncryptor.encrypt(
                raw, captchaEkey, captchaSceneIdTtlSeconds);
    }

    public String sceneId() {
        return captchaSceneId == null ? "" : captchaSceneId;
    }

    public String prefix() {
        return captchaPrefix == null ? "" : captchaPrefix;
    }

    public String captchaRegion() {
        return captchaRegion == null || captchaRegion.isBlank() ? "cn-shanghai" : captchaRegion;
    }

    /** All defaults: the demo is disabled and every limit is the production value. */
    public static DemoProperties defaults() {
        return new DemoProperties(false, 2, 10, 80, 50, 10, 1, true, "", "", "cn-shanghai",
                "", "", "", false, 3600, false);
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
