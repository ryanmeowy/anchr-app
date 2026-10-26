package com.anchr.core.integration.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vendor expects a specific byte layout; getting it wrong makes every visitor fail with no
 * useful error, so the layout is verified by decrypting exactly as the service would.
 */
class CaptchaSceneIdEncryptorTest {

    private static final String EKEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void outputShouldBeIvFollowedByCiphertext() throws Exception {
        String encrypted = CaptchaSceneIdEncryptor.encrypt("scene-1", EKEY, 3600);

        byte[] combined = Base64.getDecoder().decode(encrypted);
        // 16-byte IV plus at least one cipher block.
        assertThat(combined.length).isGreaterThan(16);

        byte[] iv = new byte[16];
        byte[] ciphertext = new byte[combined.length - 16];
        System.arraycopy(combined, 0, iv, 0, 16);
        System.arraycopy(combined, 16, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(EKEY), "AES"),
                new IvParameterSpec(iv));
        String plaintext = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        String[] parts = plaintext.split("&");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("scene-1");
        assertThat(Long.parseLong(parts[1])).isBetween(
                System.currentTimeMillis() / 1000 - 60, System.currentTimeMillis() / 1000 + 60);
        assertThat(parts[2]).isEqualTo("3600");
    }

    @Test
    void everyCallShouldUseAFreshIv() {
        String first = CaptchaSceneIdEncryptor.encrypt("scene-1", EKEY, 3600);
        String second = CaptchaSceneIdEncryptor.encrypt("scene-1", EKEY, 3600);

        // Reusing an IV would make the two ciphertexts share a prefix after the IV.
        assertThat(first).isNotEqualTo(second);
        assertThat(first.substring(24)).isNotEqualTo(second.substring(24));
    }

    @Test
    void shouldRejectAKeyOfTheWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CaptchaSceneIdEncryptor.encrypt("scene-1", shortKey, 3600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void shouldRejectATtlOutsideTheDocumentedRange() {
        assertThatThrownBy(() -> CaptchaSceneIdEncryptor.encrypt("scene-1", EKEY, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaptchaSceneIdEncryptor.encrypt("scene-1", EKEY, 86_401))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankInputs() {
        assertThatThrownBy(() -> CaptchaSceneIdEncryptor.encrypt("", EKEY, 3600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaptchaSceneIdEncryptor.encrypt("scene-1", "", 3600))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
