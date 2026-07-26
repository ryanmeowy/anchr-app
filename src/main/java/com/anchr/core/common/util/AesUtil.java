package com.anchr.core.common.util;

import com.anchr.core.common.exception.EncryptionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.SecureRandom;

/**
 * Utility class for AES encryption and decryption.
 * This class provides methods to encrypt and decrypt strings using AES algorithm.
 * The encryption key and initialization vector (IV) are injected from application properties.
 *
 */
@Component
public class AesUtil {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private final String keyBase64;
    private final String ivBase64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public AesUtil(@Value("${app.security.encrypt-key}") String keyBase64,
                   @Value("${app.security.encrypt-iv}") String ivBase64) {
        this.keyBase64 = keyBase64;
        this.ivBase64 = ivBase64;
    }

    private byte[] decodeKey() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new EncryptionException("Encryption key is not configured");
        }
        return Base64.getDecoder().decode(keyBase64);
    }

    private byte[] decodeIv() {
        if (ivBase64 == null || ivBase64.isBlank()) {
            throw new EncryptionException("Encryption iv is not configured");
        }
        return Base64.getDecoder().decode(ivBase64);
    }

    private SecretKeySpec keySpec() {
        byte[] keyBytes = decodeKey();
        if (keyBytes.length != 32) {
            throw new EncryptionException("Key must be 32 bytes (AES-256)");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private IvParameterSpec ivSpec() {
        byte[] ivBytes = decodeIv();
        if (ivBytes.length != 16) {
            throw new EncryptionException("IV must be 16 bytes");
        }
        return new IvParameterSpec(ivBytes);
    }

    public String encrypt(String content) {
        if (content == null) {
            throw new EncryptionException("Content to encrypt must not be null");
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), ivSpec());
            byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null) {
            throw new EncryptionException("Ciphertext must not be null");
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), ivSpec());
            byte[] decoded = Base64.getDecoder().decode(base64Ciphertext);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    /** Encrypts a short-lived cross-service secret with authenticated context binding. */
    public AeadEnvelope encryptAead(String content, String aad) {
        if (content == null || aad == null) {
            throw new EncryptionException("AEAD content and AAD must not be null");
        }
        try {
            byte[] nonce = new byte[12];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            int tagOffset = sealed.length - 16;
            return new AeadEnvelope(
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(
                            java.util.Arrays.copyOfRange(sealed, 0, tagOffset)),
                    Base64.getEncoder().encodeToString(
                            java.util.Arrays.copyOfRange(sealed, tagOffset, sealed.length)));
        } catch (Exception e) {
            throw new EncryptionException("AEAD encryption failed", e);
        }
    }

    public record AeadEnvelope(String nonce, String ciphertext, String tag) {}
}
