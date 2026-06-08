package com.anchr.core.integration.multimodal.service.cloud.volcengine;

import com.anchr.core.integration.multimodal.manager.volcengine.VolcengineEmbeddingManager;
import com.anchr.core.integration.multimodal.embedding.EmbeddingBackend;
import com.anchr.core.settings.application.provider.ProviderIdentity;
import com.anchr.core.settings.domain.model.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.embedding", name = "backend", havingValue = "volcengine")
public class VolcengineEmbeddingService implements EmbeddingBackend, ProviderIdentity {

    private final VolcengineEmbeddingManager volcengineEmbeddingManager;

    @Override
    public String backendName() {
        return "volcengine";
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.EMBEDDING;
    }

    @Override
    public String providerName() {
        return backendName();
    }

    /**
     * Get multimodal vector (image)
     *
     * @param imageUrl Image URL
     * @return vector
     */
    @Override
    public List<Float> embedImage(String imageUrl) {
        return volcengineEmbeddingManager.embedImage(imageUrl);
    }

    /**
     * Get multimodal vector (image bytes).
     *
     * @param imageBytes image bytes
     * @param mimeType   image mime type, e.g. image/jpeg
     * @return vector
     */
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
     * @return vector
     */
    @Override
    public List<Float> embedText(String text) {
        return volcengineEmbeddingManager.embedText(text);
    }
}
