package com.anchr.core.kb.application;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Calculates backend-owned document content hashes.
 */
@Service
public class DocumentHashService {

    private static final int BUFFER_SIZE = 8192;

    public String sha256(byte[] content) {
        if (content == null) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "content cannot be null.");
        }
        MessageDigest digest = newDigest();
        return HexFormat.of().formatHex(digest.digest(content));
    }

    public String sha256(InputStream inputStream) {
        if (inputStream == null) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "inputStream cannot be null.");
        }
        try {
            MessageDigest digest = newDigest();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to calculate document hash.", e);
        }
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "SHA-256 algorithm is unavailable.", e);
        }
    }
}
