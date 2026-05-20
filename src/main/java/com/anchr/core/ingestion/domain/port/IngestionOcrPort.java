package com.anchr.core.ingestion.domain.port;

import com.anchr.core.ingestion.domain.model.OcrStructuredResult;

/**
 * Domain port for OCR extraction in ingestion.
 */
public interface IngestionOcrPort {

    /**
     * Extract OCR text from image input.
     *
     * @param imageInput image url/data input
     * @return OCR text
     */
    String extractText(String imageInput);

    OcrStructuredResult extractStructuredText(String imageInput);
}
