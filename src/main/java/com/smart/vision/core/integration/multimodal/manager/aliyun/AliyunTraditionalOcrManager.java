package com.smart.vision.core.integration.multimodal.manager.aliyun;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAdvancedRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAdvancedResponse;
import com.aliyun.ocr_api20210707.models.RecognizeAdvancedResponseBody;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.common.exception.InfraException;
import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Aliyun traditional OCR adapter for paragraph-level bbox extraction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "ocr", havingValue = "aliyun")
public class AliyunTraditionalOcrManager {

    private final Client ocrClient;
    private final AliyunAdvancedOcrResultParser resultParser;

    @Retryable(retryFor = {Exception.class}, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OcrStructuredResult recognize(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "imageUrl cannot be blank.");
        }
        try {
            RecognizeAdvancedRequest request = new RecognizeAdvancedRequest()
                    .setUrl(imageUrl)
                    .setParagraph(true)
                    .setOutputCharInfo(false)
                    .setOutputTable(false);
            RecognizeAdvancedResponse response = ocrClient.recognizeAdvanced(request);
            RecognizeAdvancedResponseBody body = response == null ? null : response.getBody();
            validateResponse(body);
            return resultParser.parse(body.getData());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Aliyun traditional OCR failed", e);
            throw new InfraException(ApiError.INTERNAL_ERROR, "Aliyun traditional OCR failed", e);
        }
    }

    private void validateResponse(RecognizeAdvancedResponseBody body) {
        if (body == null) {
            throw new InfraException(ApiError.INTERNAL_ERROR, "Aliyun OCR response body is empty");
        }
        String code = body.getCode();
        if (StringUtils.hasText(code) && !"200".equals(code)) {
            String message = StringUtils.hasText(body.getMessage()) ? body.getMessage() : "Aliyun OCR returned error";
            throw new InfraException(ApiError.INTERNAL_ERROR, message);
        }
    }
}
