package com.anchr.core.integration.multimodal.manager.volcengine;

import com.anchr.core.common.config.EmbeddingProperties;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingInput;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingRequest;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingResult;
import com.volcengine.ark.runtime.service.ArkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VolcengineEmbeddingManager {

    private final ObjectProvider<ArkService> arkServiceProvider;
    private final EmbeddingProperties embeddingProperties;

    @Retryable(
            retryFor = {Exception.class},
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<Float> embedImage(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "imageUrl cannot be blank.");
        }
        MultimodalEmbeddingInput input = MultimodalEmbeddingInput.builder()
                .type("image_url")
                .imageUrl(new MultimodalEmbeddingInput.MultiModalEmbeddingContentPartImageURL(imageUrl))
                .build();
        return callSdk(List.of(input));
    }

    @Retryable(
            retryFor = {Exception.class},
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<Float> embedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "text cannot be blank.");
        }
        MultimodalEmbeddingInput input = MultimodalEmbeddingInput.builder()
                .text(text)
                .build();
        return callSdk(List.of(input));
    }

    private List<Float> callSdk(List<MultimodalEmbeddingInput> inputs) {
        MultimodalEmbeddingRequest multiModalEmbeddingRequest = MultimodalEmbeddingRequest.builder()
                .model(embeddingProperties.getModel())
                .input(inputs)
                .build();

        MultimodalEmbeddingResult res = arkServiceProvider.getObject().createMultiModalEmbeddings(multiModalEmbeddingRequest);

        if (null == res || null == res.getData()) {
            log.info("embedding failed, request:{}, response:{}", inputs, res);
            throw new BusinessException(ApiError.EMBEDDING_FAILED);
        }

        if (CollectionUtils.isEmpty(res.getData().getEmbedding())) {
            throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY, "Volcengine returned empty embedding.");
        }
        List<Double> rawEmbeddingList = res.getData().getEmbedding();

        return rawEmbeddingList.stream()
                .filter(Objects::nonNull)
                .map(Double::floatValue)
                .collect(Collectors.toList());
    }

}
