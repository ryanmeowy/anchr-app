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
        /** Plain scenario id; always sent, even in encryption mode. */
        String sceneId,
        String prefix,
        /**
         * AES token to send alongside sceneId when encryption mode is on. Null otherwise.
         */
        String encryptedSceneId
) {
}
