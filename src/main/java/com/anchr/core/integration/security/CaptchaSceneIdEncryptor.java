package com.anchr.core.integration.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Produces the {@code EncryptedSceneId} that Alibaba Cloud CAPTCHA 2.0 requires when the scenario
 * runs in encryption mode.
 *
 * <p>Encryption mode stops the raw scene id from being published in the page: the server encrypts
 * {@code sceneId&timestamp&expireTime} with the console-provided {@code ekey}, prepends a random
 * IV, and Base64-encodes the result. The CAPTCHA service then rejects anything it cannot decrypt
 * or that has expired.
 *
 * <p>Layout, per the vendor specification:
 * <pre>
 *   plaintext = sceneId + "&" + epochSeconds + "&" + ttlSeconds
 *   key       = Base64.decode(ekey)            -- 32 bytes, AES-256
 *   cipher    = AES/CBC/PKCS5Padding (equivalent to PKCS7 for AES)
 *   output    = Base64(iv || ciphertext)       -- iv is 16 random bytes, never reused
 * </pre>
 *
 * <p>When the scenario is not in encryption mode the plain scene id is passed through unchanged,
 * so the toggle stays on the server rather than in the browser.
 */
public final class CaptchaSceneIdEncryptor {

    private static final int IV_LENGTH = 16;
    private static final int AES_256_KEY_BYTES = 32;
    private static final int MAX_TTL_SECONDS = 86_400;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CaptchaSceneIdEncryptor() {
    }

    /**
     * @param sceneId      scenario id from the console
     * @param ekey         console-provided key, Base64 encoded
     * @param ttlSeconds   how long the result stays valid, 1..86400
     * @return Base64 of {@code iv || ciphertext}
     */
    public static String encrypt(String sceneId, String ekey, int ttlSeconds) {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (ekey == null || ekey.isBlank()) {
            throw new IllegalArgumentException("ekey must not be blank");
        }
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("ttlSeconds must be between 1 and 86400");
        }
        byte[] key = Base64.getDecoder().decode(ekey);
        if (key.length != AES_256_KEY_BYTES) {
            // A wrong-shaped key would produce a value the service silently cannot decrypt, which
            // surfaces as every visitor being rejected. Fail loudly at the boundary instead.
            throw new IllegalArgumentException(
                    "ekey must decode to " + AES_256_KEY_BYTES + " bytes, got " + key.length);
        }
        try {
            long timestamp = Instant.now().getEpochSecond();
            String plaintext = sceneId + "&" + timestamp + "&" + ttlSeconds;
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to encrypt CAPTCHA scene id", exception);
        }
    }
}
