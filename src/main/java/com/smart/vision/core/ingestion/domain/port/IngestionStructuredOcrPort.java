package com.smart.vision.core.ingestion.domain.port;

import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;

/**
 * Domain port for traditional OCR with paragraph bboxes.
 */
public interface IngestionStructuredOcrPort {

    OcrStructuredResult extractStructuredText(String imageInput);
}
