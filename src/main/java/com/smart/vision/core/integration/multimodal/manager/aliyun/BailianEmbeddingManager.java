package com.smart.vision.core.integration.multimodal.manager.aliyun;

import com.alibaba.dashscope.embeddings.MultiModalEmbedding;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemBase;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemImage;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemText;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingParam;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResult;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResultItem;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.google.common.collect.Lists;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.common.util.VectorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.smart.vision.core.integration.constant.AliyunConstant.BAILIAN_API_KEY_ENV_NAME;
import static com.smart.vision.core.integration.constant.AliyunConstant.EMBEDDING_MODEL_NAME;

/**
 * Bailian embedding manager for handling multimodal embeddings
 * This manager integrates with Aliyun Bailian service to generate
 * vector representations of images and text for semantic search capabilities
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "embedding", havingValue = "aliyun")
public class BailianEmbeddingManager {

    /**
     * call Aliyun to generate vectors,
     * it automatically retries up to 3 times, with intervals of 1 second, 2 seconds, and 4 seconds (multiplier=2) between attempts.
     */
    @Retryable(
            retryFor = {Exception.class},
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<Float> embedImage(String imageUrl) throws NoApiKeyException, UploadFileException, ApiException {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "imageUrl cannot be blank.");
        }
        MultiModalEmbeddingItemBase item = new MultiModalEmbeddingItemImage(imageUrl);
        return VectorUtil.l2Normalize(callSdk(Lists.newArrayList(item)));
    }

    @Retryable(
            retryFor = {Exception.class},
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<Float> embedText(String text) throws NoApiKeyException, UploadFileException {
        if (text == null || text.trim().isEmpty()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "text cannot be blank.");
        }
        MultiModalEmbeddingItemBase item = new MultiModalEmbeddingItemText(text);
        return VectorUtil.l2Normalize(callSdk(Lists.newArrayList(item)));
    }

    private List<Float> callSdk(List<MultiModalEmbeddingItemBase> inputItem) throws NoApiKeyException, UploadFileException {
        MultiModalEmbeddingParam param = MultiModalEmbeddingParam.builder()
                .apiKey(resolveApiKey())
                .model(EMBEDDING_MODEL_NAME)
                .contents(inputItem)
                .build();

        MultiModalEmbedding embedder = new MultiModalEmbedding();
        MultiModalEmbeddingResult result = embedder.call(param);

        if (result.getOutput() == null || CollectionUtils.isEmpty(result.getOutput().getEmbeddings())) {
            throw new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY, "Aliyun returned empty embedding result.");
        }

        List<Double> rawEmbedding = result.getOutput().getEmbeddings().stream()
                .findFirst()
                .map(MultiModalEmbeddingResultItem::getEmbedding)
                .orElseThrow(() -> new BusinessException(ApiError.EMBEDDING_RESULT_EMPTY, "Aliyun returned empty embedding vector."));

        return rawEmbedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());
    }

    private String resolveApiKey() {
        return Optional.ofNullable(System.getenv(BAILIAN_API_KEY_ENV_NAME))
                .orElseThrow(() -> new BusinessException(ApiError.INVALID_API_KEY));
    }
}
