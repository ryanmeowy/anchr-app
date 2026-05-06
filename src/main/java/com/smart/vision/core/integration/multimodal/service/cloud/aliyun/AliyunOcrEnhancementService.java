package com.smart.vision.core.integration.multimodal.service.cloud.aliyun;

import com.smart.vision.core.ingestion.domain.model.OcrBoundingBox;
import com.smart.vision.core.ingestion.domain.model.OcrParagraph;
import com.smart.vision.core.integration.multimodal.manager.aliyun.AliyunGenManager;
import com.smart.vision.core.integration.multimodal.port.OcrParagraphEnhancementPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Aliyun VL adapter for vision-grounded OCR paragraph enhancement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "gen", havingValue = "aliyun")
public class AliyunOcrEnhancementService implements OcrParagraphEnhancementPort {

    private final AliyunGenManager genManager;

    @Override
    public String enhanceParagraph(String imageInput, OcrParagraph paragraph, Integer imageWidth, Integer imageHeight) {
        if (paragraph == null || paragraph.getBbox() == null) {
            return paragraph == null ? null : paragraph.getText();
        }
        try {
            return genManager.enhanceOcrParagraph(imageInput, buildPrompt(paragraph, imageWidth, imageHeight));
        } catch (Exception e) {
            log.warn("enhance OCR paragraph failed", e);
            return paragraph.getText();
        }
    }

    private String buildPrompt(OcrParagraph paragraph, Integer imageWidth, Integer imageHeight) {
        OcrBoundingBox box = paragraph.getBbox();
        return """
                Fix only obvious OCR recognition errors in the target image region.
                Use the image as visual evidence. Do not add information outside the region.
                Return only the corrected text. If uncertain, return the original OCR text.

                Original image size: %s x %s pixels
                Target bbox: x=%s, y=%s, width=%s, height=%s, unit=%s
                Original OCR text:
                %s
                """.formatted(
                imageWidth,
                imageHeight,
                box.getX(),
                box.getY(),
                box.getWidth(),
                box.getHeight(),
                box.getUnit(),
                paragraph.getText()
        );
    }
}
