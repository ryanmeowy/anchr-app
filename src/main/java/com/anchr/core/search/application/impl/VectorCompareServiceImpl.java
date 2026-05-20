package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.VectorUtil;
import com.anchr.core.search.application.VectorCompareService;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.interfaces.rest.dto.VectorCompareResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.anchr.core.common.constant.CacheConstant.COMPARE_IMAGE_CACHE_PREFIX;
import static com.anchr.core.common.constant.CacheConstant.COMPARE_TEXT_CACHE_PREFIX;
import static com.anchr.core.common.constant.SearchConstant.IMAGE_MAX_SIZE;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorCompareServiceImpl implements VectorCompareService {

    private static final String INPUT_TYPE_TEXT = "text";
    private static final String INPUT_TYPE_IMAGE = "image";
    private static final long CACHE_TTL_HOURS = 24L;

    @Value("${app.compare.max-text-length:500}")
    private int maxTextLength;

    @Value("${app.compare.high-threshold:0.80}")
    private double highThreshold;

    @Value("${app.compare.medium-threshold:0.60}")
    private double mediumThreshold;

    @Value("${app.embedding.image-input-mode:auto}")
    private String imageInputMode;
    @Value("${app.capability.ai.provider:local}")
    private String aiProvider;

    private final SearchEmbeddingPort embeddingPort;
    private final RedisTemplate<String, List<Float>> redisTemplate;
    private final SearchObjectStoragePort objectStoragePort;

    @Override
    public VectorCompareResultDTO compare(String leftType,
                                          String leftText,
                                          MultipartFile leftFile,
                                          String rightType,
                                          String rightText,
                                          MultipartFile rightFile) {
        long startNs = System.nanoTime();
        String normalizedLeftType = normalizeAndValidateType(leftType, "leftType");
        String normalizedRightType = normalizeAndValidateType(rightType, "rightType");

        EmbeddingResolveResult left = resolveEmbedding(normalizedLeftType, leftText, leftFile, "left");
        EmbeddingResolveResult right = resolveEmbedding(normalizedRightType, rightText, rightFile, "right");

        if (left.vector.size() != right.vector.size()) {
            throw new IllegalArgumentException("Vector dimensions are inconsistent; cannot compare.");
        }

        double cosine = VectorUtil.cosineSimilarity(left.vector, right.vector);
        double scorePercent = normalizePercent(cosine);
        String matchLevel = toMatchLevel(cosine);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        return VectorCompareResultDTO.builder()
                .leftType(normalizedLeftType)
                .rightType(normalizedRightType)
                .cosineSimilarity(round(cosine))
                .scorePercent(round(scorePercent))
                .matchLevel(matchLevel)
                .leftDimension(left.vector.size())
                .rightDimension(right.vector.size())
                .leftNorm(round(VectorUtil.l2Norm(left.vector)))
                .rightNorm(round(VectorUtil.l2Norm(right.vector)))
                .leftCacheHit(left.cacheHit)
                .rightCacheHit(right.cacheHit)
                .elapsedMs(elapsedMs)
                .build();
    }

    private EmbeddingResolveResult resolveEmbedding(String inputType,
                                                    String inputText,
                                                    MultipartFile inputFile,
                                                    String sideName) {
        if (INPUT_TYPE_TEXT.equals(inputType)) {
            return resolveTextEmbedding(inputText, sideName);
        }
        return resolveImageEmbedding(inputFile, sideName);
    }

    private EmbeddingResolveResult resolveTextEmbedding(String text, String sideName) {
        String normalizedText = validateText(text, sideName);
        String cacheKey = buildTextCacheKey(normalizedText);
        List<Float> vector = redisTemplate.opsForValue().get(cacheKey);
        boolean cacheHit = vector != null && !vector.isEmpty();
        if (cacheHit) {
            return new EmbeddingResolveResult(vector, true);
        }

        vector = embeddingPort.embedText(normalizedText);
        validateVector(vector, sideName);
        redisTemplate.opsForValue().set(cacheKey, vector, CACHE_TTL_HOURS, TimeUnit.HOURS);
        return new EmbeddingResolveResult(vector, false);
    }

    private EmbeddingResolveResult resolveImageEmbedding(MultipartFile file, String sideName) {
        validateImage(file, sideName);
        try {
            byte[] bytes = file.getBytes();
            String md5 = DigestUtils.md5DigestAsHex(bytes);
            String cacheKey = buildImageCacheKey(md5);
            List<Float> vector = redisTemplate.opsForValue().get(cacheKey);
            boolean cacheHit = vector != null && !vector.isEmpty();
            if (cacheHit) {
                return new EmbeddingResolveResult(vector, true);
            }

            if (shouldUseBytesInput()) {
                vector = embeddingPort.embedImage(bytes, file.getContentType());
            } else {
                String objectKey = objectStoragePort.uploadFile(file);
                String tempAiUrl = objectStoragePort.buildAiImageInput(objectKey, SearchObjectStoragePort.AiInputValidity.SHORT);
                vector = embeddingPort.embedImage(tempAiUrl);
            }
            validateVector(vector, sideName);
            redisTemplate.opsForValue().set(cacheKey, vector, CACHE_TTL_HOURS, TimeUnit.HOURS);
            return new EmbeddingResolveResult(vector, false);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to embed image for {} side", sideName, e);
            throw new BusinessException(ApiError.EMBEDDING_FAILED);
        }
    }

    private void validateVector(List<Float> vector, String sideName) {
        if (vector == null || vector.isEmpty()) {
            throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY,
                    "Embedding result is empty for " + sideName + " input.");
        }
    }

    private String normalizeAndValidateType(String type, String fieldName) {
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException(fieldName + " is required and must be text or image.");
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (!INPUT_TYPE_TEXT.equals(normalized) && !INPUT_TYPE_IMAGE.equals(normalized)) {
            throw new IllegalArgumentException(fieldName + " is invalid, only text or image is allowed.");
        }
        return normalized;
    }

    private String validateText(String text, String sideName) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException(sideName + " text is required.");
        }
        String normalized = text.trim();
        if (normalized.length() > maxTextLength) {
            throw new IllegalArgumentException(sideName + " text is too long, max length is " + maxTextLength + ".");
        }
        return normalized;
    }

    private void validateImage(MultipartFile file, String sideName) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(sideName + " image file is required.");
        }
        if (file.getSize() > IMAGE_MAX_SIZE) {
            throw new IllegalArgumentException(sideName + " image exceeds 10MB limit.");
        }
    }

    private String buildTextCacheKey(String text) {
        String textHash = DigestUtils.md5DigestAsHex(text.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return String.format("%s%s", COMPARE_TEXT_CACHE_PREFIX, textHash);
    }

    private String buildImageCacheKey(String md5) {
        return String.format("%s%s", COMPARE_IMAGE_CACHE_PREFIX, md5);
    }

    private boolean shouldUseBytesInput() {
        String mode = imageInputMode == null ? "auto" : imageInputMode.trim().toLowerCase(Locale.ROOT);
        if ("bytes".equals(mode)) {
            return true;
        }
        if ("url".equals(mode)) {
            return false;
        }
        return "local".equalsIgnoreCase(aiProvider);
    }

    private double normalizePercent(double cosine) {
        double normalized = (cosine + 1d) / 2d;
        double bounded = Math.max(0d, Math.min(1d, normalized));
        return bounded * 100d;
    }

    private String toMatchLevel(double cosine) {
        if (cosine >= highThreshold) {
            return "HIGH";
        }
        if (cosine >= mediumThreshold) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private record EmbeddingResolveResult(List<Float> vector, boolean cacheHit) {
    }
}
