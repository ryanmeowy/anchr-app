package com.smart.vision.core.integration.multimodal.service.local;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.grpc.VisionProto;
import com.smart.vision.core.grpc.VisionServiceGrpc;
import com.smart.vision.core.ingestion.domain.model.OcrParagraph;
import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;
import com.smart.vision.core.ingestion.domain.port.IngestionOcrPort;
import com.smart.vision.core.integration.multimodal.domain.model.PromptEnum;
import com.smart.vision.core.integration.multimodal.support.OcrStructuredResultPostProcessor;
import com.smart.vision.core.search.domain.port.SearchOcrPort;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "ocr", havingValue = "local")
public class LocalOcrService implements SearchOcrPort, IngestionOcrPort {

    @SuppressWarnings("unused")
    @GrpcClient("vision-python-service")
    private VisionServiceGrpc.VisionServiceBlockingStub visionStub;

    @Value("${grpc.deadline.ocr-ms:8000}")
    private long ocrDeadlineMs;
    private final OcrStructuredResultPostProcessor ocrPostProcessor;

    @Override
    public String extractText(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "imageUrl cannot be blank.");
        }
        long startNs = System.nanoTime();
        try {
            VisionProto.GenRequest request = VisionProto.GenRequest.newBuilder()
                    .setImageUrl(imageUrl)
                    .setPrompt(PromptEnum.OCR.getPrompt())
                    .build();
            VisionProto.OcrResponse ocrResponse = visionStub
                    .withDeadlineAfter(ocrDeadlineMs, TimeUnit.MILLISECONDS)
                    .extractText(request);
            return ocrResponse.getFullText();
        } catch (StatusRuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            log.error("gRPC extractText failed: statusCode={}, deadlineMs={}, elapsedMs={}", e.getStatus().getCode(), ocrDeadlineMs, elapsedMs, e);
            throw new RuntimeException("extract text failed, try again later.");
        } catch (Exception e) {
            log.error("gRPC ocr call failed: {}", e.getMessage());
            throw new RuntimeException("extract text failed, try again later.");
        }
    }

    @Override
    public OcrStructuredResult extractStructuredText(String imageUrl) {
        String text = extractText(imageUrl);
        OcrStructuredResult baseResult;
        if (!StringUtils.hasText(text)) {
            baseResult = OcrStructuredResult.builder()
                    .paragraphs(List.of())
                    .build();
        } else {
            baseResult = OcrStructuredResult.builder()
                    .fullText(text)
                    .paragraphs(List.of(OcrParagraph.builder()
                            .index(0)
                            .text(text)
                            .words(List.of())
                            .build()))
                    .build();
        }
        return ocrPostProcessor.process(imageUrl, baseResult);
    }
}
