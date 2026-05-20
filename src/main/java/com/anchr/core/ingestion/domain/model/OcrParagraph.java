package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Paragraph-level OCR unit used before image segment indexing.
 */
@Value
@Builder
public class OcrParagraph {

    Integer index;
    String text;
    OcrBoundingBox bbox;
    List<OcrWord> words;
}
