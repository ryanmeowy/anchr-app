package com.anchr.core.auth.interfaces.rest.dto;

/**
 * Public demo status. Carries no secret.
 *
 * @param sceneId  CAPTCHA scenario id; public by design, the browser needs it to render
 * @param prefix   CAPTCHA app identifier used by the client SDK
 */
public record DemoStatusDTO(
        boolean enabled,
        boolean closed,
        int tokenQuota,
        String sceneId,
        String prefix,
        /** True when sceneId is an AES token that must be sent as EncryptedSceneId. */
        boolean sceneIdEncrypted
) {
}
