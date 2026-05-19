package com.smart.vision.core.auth.infrastructure;

import com.smart.vision.core.auth.EncryptionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.smart.vision.core.common.constant.CommonConstant.ALGORITHM;

/**
 * Utility class for AES encryption and decryption.
 * This class provides methods to encrypt and decrypt strings using AES algorithm.
 * The encryption key and initialization vector (IV) are injected from application properties.
 *
 * @author Ryan
 * @since 2025/12/25
 */
@Component
public class AesUtil {

    private final String keyBase64;
    private final String ivBase64;

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

    @Deprecated
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
}
