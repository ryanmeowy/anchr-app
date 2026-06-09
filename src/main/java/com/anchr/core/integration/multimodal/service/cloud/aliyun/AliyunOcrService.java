package com.anchr.core.integration.multimodal.service.cloud.aliyun;

import com.alibaba.dashscope.exception.NoApiKeyException;
import com.anchr.core.ingestion.domain.model.OcrStructuredResult;
import com.anchr.core.ingestion.domain.port.IngestionOcrPort;
import com.anchr.core.integration.multimodal.domain.model.AliyunErrorCode;
import com.anchr.core.integration.multimodal.manager.aliyun.AliyunOcrManager;
import com.anchr.core.integration.multimodal.manager.aliyun.AliyunTraditionalOcrManager;
import com.anchr.core.integration.multimodal.support.OcrStructuredResultPostProcessor;
import com.anchr.core.search.domain.port.SearchOcrPort;
import com.anchr.core.settings.application.provider.ProviderIdentity;
import com.anchr.core.settings.domain.model.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AliyunOcrService implements SearchOcrPort, IngestionOcrPort, ProviderIdentity {

    private final AliyunOcrManager ocrManager;
    private final ObjectProvider<AliyunTraditionalOcrManager> traditionalOcrManagerProvider;
    private final OcrStructuredResultPostProcessor ocrPostProcessor;

    @Override
    public ProviderType providerType() {
        return ProviderType.OCR;
    }

    @Override
    public String providerName() {
        return "aliyun";
    }

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
        AliyunTraditionalOcrManager traditionalOcrManager = traditionalOcrManagerProvider.getIfAvailable();
        if (traditionalOcrManager == null) {
            throw new RuntimeException("Aliyun traditional OCR client is unavailable.");
        }
        return ocrPostProcessor.process(imageUrl, traditionalOcrManager.recognize(imageUrl));
    }
}
