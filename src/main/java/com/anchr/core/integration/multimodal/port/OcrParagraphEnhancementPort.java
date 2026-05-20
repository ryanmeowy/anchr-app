package com.anchr.core.integration.multimodal.port;

import com.anchr.core.ingestion.domain.model.OcrParagraph;

/**
 * Integration-side OCR paragraph enhancement abstraction.
 */
public interface OcrParagraphEnhancementPort {

    String enhanceParagraph(String imageInput, OcrParagraph paragraph, Integer imageWidth, Integer imageHeight);
}
