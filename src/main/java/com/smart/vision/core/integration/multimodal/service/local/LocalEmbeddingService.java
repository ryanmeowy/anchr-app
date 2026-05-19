package com.smart.vision.core.integration.multimodal.service.local;

import com.google.protobuf.ByteString;
import com.smart.vision.core.grpc.VisionProto;
import com.smart.vision.core.grpc.VisionServiceGrpc;
import com.smart.vision.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.smart.vision.core.search.domain.port.SearchEmbeddingPort;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.capability-provider", name = "embedding", havingValue = "local")
public class LocalEmbeddingService implements SearchEmbeddingPort, IngestionEmbeddingPort {

    @SuppressWarnings("unused")
    @GrpcClient("vision-python-service")
    private VisionServiceGrpc.VisionServiceBlockingStub visionStub;

    @Value("${grpc.deadline.embed-image-ms:5000}")
    private long embedImageDeadlineMs;

    @Value("${grpc.deadline.embed-text-ms:5000}")
    private long embedTextDeadlineMs;

    /**
     * Get multimodal vector (image)
     *
     * @param imageUrl Image URL
     * @return 512-dimensional vector
     */
    @Override
    public List<Float> embedImage(String imageUrl) {
        long startNs = System.nanoTime();
        try {
            VisionProto.ImageRequest request = VisionProto.ImageRequest.newBuilder().setUrl(imageUrl).build();
            VisionProto.EmbeddingResponse embeddingResponse = visionStub
                    .withDeadlineAfter(embedImageDeadlineMs, TimeUnit.MILLISECONDS)
                    .embedImage(request);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.debug("gRPC embedImage success: deadlineMs={}, elapsedMs={}", embedImageDeadlineMs, elapsedMs);
            return embeddingResponse.getVectorList();
        } catch (StatusRuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.error("gRPC embedImage failed: statusCode={}, deadlineMs={}, elapsedMs={}", e.getStatus().getCode(), embedImageDeadlineMs, elapsedMs, e);
            throw new RuntimeException("embed image failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC image embedding call failed", e);
            throw new RuntimeException("embed image failed, try again later.");
        }
    }

    @Override
    public List<Float> embedImage(byte[] imageBytes, String mimeType) {
        long startNs = System.nanoTime();
        try {
            VisionProto.ImageRequest request = VisionProto.ImageRequest.newBuilder()
                    .setImageBytes(ByteString.copyFrom(imageBytes))
                    .setMimeType(mimeType == null ? "" : mimeType)
                    .build();
            VisionProto.EmbeddingResponse embeddingResponse = visionStub
                    .withDeadlineAfter(embedImageDeadlineMs, TimeUnit.MILLISECONDS)
                    .embedImage(request);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.debug("gRPC embedImage(bytes) success: deadlineMs={}, elapsedMs={}", embedImageDeadlineMs, elapsedMs);
            return embeddingResponse.getVectorList();
        } catch (StatusRuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.error("gRPC embedImage(bytes) failed: statusCode={}, deadlineMs={}, elapsedMs={}", e.getStatus().getCode(), embedImageDeadlineMs, elapsedMs, e);
            throw new RuntimeException("embed image failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC image embedding(bytes) call failed", e);
            throw new RuntimeException("embed image failed, try again later.");
        }
    }

    /**
     * Get multimodal vector (text)
     *
     * @param text Text
     * @return 512-dimensional vector
     */
    @Override
    public List<Float> embedText(String text) {
        long startNs = System.nanoTime();
        try {
            VisionProto.TextRequest request = VisionProto.TextRequest.newBuilder().setText(text).build();
            VisionProto.EmbeddingResponse embeddingResponse = visionStub
                    .withDeadlineAfter(embedTextDeadlineMs, TimeUnit.MILLISECONDS)
                    .embedText(request);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.debug("gRPC embedText success: deadlineMs={}, elapsedMs={}", embedTextDeadlineMs, elapsedMs);
            return embeddingResponse.getVectorList();
        } catch (StatusRuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.error("gRPC embedText failed: statusCode={}, deadlineMs={}, elapsedMs={}", e.getStatus().getCode(), embedTextDeadlineMs, elapsedMs, e);
            throw new RuntimeException("embed text failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC text embedding call failed: {}", e.getMessage());
            throw new RuntimeException("embed text failed, try again later.");
        }
    }
}
