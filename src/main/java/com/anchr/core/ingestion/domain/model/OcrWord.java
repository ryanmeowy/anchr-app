package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

/**
 * OCR word/block with its provider bbox.
 */
@Value
@Builder
public class OcrWord {

    Integer paragraphIndex;
    String text;
    OcrBoundingBox bbox;
}
