package com.anchr.core.auth.application;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2 password hashing without storing plaintext passwords.
 */
@Service
public class PasswordHashService {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "password cannot be blank.");
        }
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] hashed = pbkdf(password, salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hashed);
    }

    public boolean matches(String password, String encoded) {
        if (password == null || encoded == null || !encoded.startsWith("pbkdf2$")) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        return MessageDigest.isEqual(expected, pbkdf(password, salt, iterations));
    }

    private byte[] pbkdf(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to hash password.", e);
        }
    }
}
