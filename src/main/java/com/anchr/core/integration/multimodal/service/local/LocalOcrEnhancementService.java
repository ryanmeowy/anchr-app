package com.anchr.core.integration.multimodal.service.local;

import com.anchr.core.ingestion.domain.model.OcrParagraph;
import com.anchr.core.integration.multimodal.port.OcrParagraphEnhancementPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Local provider fallback for OCR enhancement.
 */
@Service
@ConditionalOnProperty(prefix = "app.capability-provider", name = "gen", havingValue = "local")
public class LocalOcrEnhancementService implements OcrParagraphEnhancementPort {

    @Override
    public String enhanceParagraph(String imageInput, OcrParagraph paragraph, Integer imageWidth, Integer imageHeight) {
        return paragraph == null ? null : paragraph.getText();
    }
}
