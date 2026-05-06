package com.smart.vision.core.integration.multimodal.service.cloud.aliyun;

import com.alibaba.dashscope.exception.NoApiKeyException;
import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;
import com.smart.vision.core.ingestion.domain.port.IngestionOcrPort;
import com.smart.vision.core.integration.multimodal.domain.model.AliyunErrorCode;
import com.smart.vision.core.integration.multimodal.manager.aliyun.AliyunOcrManager;
import com.smart.vision.core.integration.multimodal.manager.aliyun.AliyunTraditionalOcrManager;
import com.smart.vision.core.integration.multimodal.support.OcrStructuredResultPostProcessor;
import com.smart.vision.core.search.domain.port.SearchOcrPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "ocr", havingValue = "aliyun")
public class AliyunOcrService implements SearchOcrPort, IngestionOcrPort {

    private final AliyunOcrManager ocrManager;
    private final AliyunTraditionalOcrManager traditionalOcrManager;
    private final OcrStructuredResultPostProcessor ocrPostProcessor;

    @Override
    public String extractText(String imageUrl) {
        try {
            return ocrManager.llmOcrContent(imageUrl);
        } catch (NoApiKeyException e) {
            log.error(AliyunErrorCode.API_KEY_MISSING.getMessage(), e);
        } catch (Exception e) {
            log.warn(AliyunErrorCode.UNKNOWN.getMessage(), e);
        }
        throw new RuntimeException("OCR failed, try again later.");
    }

    @Override
    public OcrStructuredResult extractStructuredText(String imageUrl) {
        return ocrPostProcessor.process(imageUrl, traditionalOcrManager.recognize(imageUrl));
    }
}
