package com.anchr.core.common.util;

import com.anchr.core.common.exception.EncryptionException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Utility class for AES encryption and decryption.
 * This class provides methods to encrypt and decrypt strings using AES algorithm.
 * The encryption key is injected from application properties.
 *
 */
@Component
public class AesUtil {

    private static final String GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final int GCM_TAG_BITS = GCM_TAG_BYTES * Byte.SIZE;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final String keyBase64;

    public AesUtil(@Value("${app.security.encrypt-key}") String keyBase64) {
        this.keyBase64 = keyBase64;
    }

    private byte[] decodeKey() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new EncryptionException("Encryption key is not configured");
        }
        return Base64.getDecoder().decode(keyBase64);
    }

    private SecretKeySpec keySpec() {
        byte[] keyBytes = decodeKey();
        if (keyBytes.length != 32) {
            throw new EncryptionException("Key must be 32 bytes (AES-256)");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String content) {
        if (content == null) {
            throw new EncryptionException("Content to encrypt must not be null");
        }
        try {
            byte[] nonce = randomNonce();
            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(sealed, 0, envelope, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (Exception e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null) {
            throw new EncryptionException("Ciphertext must not be null");
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(base64Ciphertext);
            if (envelope.length < GCM_NONCE_BYTES + GCM_TAG_BYTES) {
                throw new EncryptionException("Ciphertext envelope is too short");
            }
            byte[] nonce = Arrays.copyOfRange(envelope, 0, GCM_NONCE_BYTES);
            byte[] sealed = Arrays.copyOfRange(envelope, GCM_NONCE_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] decrypted = cipher.doFinal(sealed);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    /** Encrypts a short-lived cross-service secret with authenticated context binding. */
    public AeadEnvelope encryptAead(String content, String aad) {
        if (content == null || aad == null) {
            throw new EncryptionException("AEAD content and AAD must not be null");
        }
        try {
            byte[] nonce = randomNonce();
            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            int tagOffset = sealed.length - GCM_TAG_BYTES;
            return new AeadEnvelope(
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(
                            Arrays.copyOfRange(sealed, 0, tagOffset)),
                    Base64.getEncoder().encodeToString(
                            Arrays.copyOfRange(sealed, tagOffset, sealed.length)));
        } catch (Exception e) {
            throw new EncryptionException("AEAD encryption failed", e);
        }
    }

    public record AeadEnvelope(String nonce, String ciphertext, String tag) {}
}
