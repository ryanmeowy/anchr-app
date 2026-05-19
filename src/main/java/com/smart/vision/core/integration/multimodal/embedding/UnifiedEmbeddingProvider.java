package com.smart.vision.core.integration.multimodal.embedding;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.smart.vision.core.search.domain.port.SearchEmbeddingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UnifiedEmbeddingProvider implements SearchEmbeddingPort, IngestionEmbeddingPort {

    private final EmbeddingBackendRegistry backendRegistry;

    @Override
    public List<Float> embedText(String text) {
        return requireVector(backendRegistry.getSelected().embedText(text));
    }

    @Override
    public List<Float> embedImage(String imageInput) {
        return requireVector(backendRegistry.getSelected().embedImage(imageInput));
    }

    @Override
    public List<Float> embedImage(byte[] imageBytes, String contentType) {
        return requireVector(backendRegistry.getSelected().embedImage(imageBytes, contentType));
    }

    private List<Float> requireVector(List<Float> vector) {
        if (CollectionUtils.isEmpty(vector)) {
            throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY);
        }
        return vector;
    }
}
