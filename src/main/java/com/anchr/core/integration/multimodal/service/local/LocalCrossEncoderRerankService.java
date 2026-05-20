package com.anchr.core.integration.multimodal.service.local;

import cn.hutool.core.collection.CollectionUtil;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.grpc.VisionProto;
import com.anchr.core.grpc.VisionServiceGrpc;
import com.anchr.core.search.domain.port.SearchRerankPort;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Local cross-encoder rerank implementation backed by gRPC Python inference service.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.capability-provider", name = "rerank", havingValue = "local")
public class LocalCrossEncoderRerankService implements SearchRerankPort {

    @SuppressWarnings("unused")
    @GrpcClient("vision-python-service")
    private VisionServiceGrpc.VisionServiceBlockingStub visionStub;

    @Value("${grpc.deadline.rerank-ms:5000}")
    private long rerankDeadlineMs;

    @Override
    public List<RerankItem> rerank(String query, List<String> documents, Integer topN) {
        String fallbackReason;
        try {
            if (!StringUtils.hasText(query) || CollectionUtil.isEmpty(documents)) {
                throw new BusinessException(ApiError.INVALID_REQUEST, "query and documents cannot be empty.");
            }
            int safeTopN = topN == null || topN <= 0 ? documents.size() : Math.min(topN, documents.size());
            return rerankByGrpc(query, documents, safeTopN);
        } catch (Exception e) {
            fallbackReason = e.getMessage();
        }
        log.warn("Rerank via integration failed, fallback to original order: {}", fallbackReason);
        return List.of();
    }

    private List<RerankItem> rerankByGrpc(String query, List<String> documents, int topN) {
        if (visionStub == null) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Local rerank grpc client is unavailable.");
        }
        long startNs = System.nanoTime();
        try {
            VisionProto.RerankRequest request = VisionProto.RerankRequest.newBuilder()
                    .setQuery(query)
                    .addAllDocuments(documents)
                    .setTopN(topN)
                    .build();
            VisionProto.RerankResponse response = visionStub
                    .withDeadlineAfter(rerankDeadlineMs, TimeUnit.MILLISECONDS)
                    .rerank(request);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.debug("gRPC rerank success: deadlineMs={}, elapsedMs={}", rerankDeadlineMs, elapsedMs);

            if (response == null || CollectionUtil.isEmpty(response.getResultsList())) {
                throw new BusinessException(ApiError.INTERNAL_ERROR, "Local rerank returned empty results.");
            }
            return response.getResultsList().stream()
                    .map(item -> new RerankItem(item.getIndex(), Math.max(0d, item.getRelevanceScore())))
                    .sorted(Comparator.comparingDouble(RerankItem::score).reversed()
                            .thenComparingInt(RerankItem::index))
                    .limit(topN)
                    .toList();
        } catch (StatusRuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.warn("gRPC rerank failed: statusCode={}, deadlineMs={}, elapsedMs={}",
                    e.getStatus().getCode(), rerankDeadlineMs, elapsedMs, e);
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Local rerank grpc call failed.", e);
        } catch (Exception e) {
            log.warn("gRPC rerank failed: {}", e.getMessage());
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Local rerank failed.", e);
        }
    }
}
