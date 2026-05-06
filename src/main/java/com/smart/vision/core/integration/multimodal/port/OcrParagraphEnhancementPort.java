package com.smart.vision.core.integration.multimodal.port;

import com.smart.vision.core.ingestion.domain.model.OcrParagraph;

/**
 * Integration-side OCR paragraph enhancement abstraction.
 */
public interface OcrParagraphEnhancementPort {

    String enhanceParagraph(String imageInput, OcrParagraph paragraph, Integer imageWidth, Integer imageHeight);
}
