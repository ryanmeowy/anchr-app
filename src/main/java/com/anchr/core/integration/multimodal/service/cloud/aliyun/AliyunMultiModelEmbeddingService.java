package com.anchr.core.integration.multimodal.service.cloud.aliyun;

import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.anchr.core.integration.multimodal.manager.aliyun.BailianEmbeddingManager;
import com.anchr.core.integration.multimodal.domain.model.AliyunErrorCode;
import com.anchr.core.integration.multimodal.embedding.EmbeddingBackend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.embedding", name = "backend", havingValue = "aliyun")
public class AliyunMultiModelEmbeddingService implements EmbeddingBackend {

    private final BailianEmbeddingManager bailianManager;

    @Override
    public String backendName() {
        return "aliyun";
    }

    /**
     * Get multimodal vector (image)
     *
     * @param imageUrl Image URL
     * @return 1024-dimensional vector
     */
    @Override
    public List<Float> embedImage(String imageUrl) {
        try {
            return bailianManager.embedImage(imageUrl);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (ApiException e) {
            log.error(AliyunErrorCode.CALL_FAILED.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("embed image failed, try again later.");
    }

    @Override
    public List<Float> embedImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new RuntimeException("image bytes is empty");
        }
        String safeMimeType = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUri = "data:" + safeMimeType + ";base64," + base64;
        return embedImage(dataUri);
    }

    /**
     * Get multimodal vector (text)
     *
     * @param text Text
     * @return 1024-dimensional vector
     */
    @Override
    public List<Float> embedText(String text) {
        try {
            return bailianManager.embedText(text);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (ApiException e) {
            log.error(AliyunErrorCode.CALL_FAILED.getMessage(), e);
        } catch (Exception e) {
            log.error(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("embed text failed, try again later.");
    }
}
