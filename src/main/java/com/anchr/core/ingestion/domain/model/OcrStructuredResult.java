package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Structured OCR result from a traditional OCR provider.
 */
@Value
@Builder
public class OcrStructuredResult {

    String fullText;
    Integer imageWidth;
    Integer imageHeight;
    List<OcrParagraph> paragraphs;
}
