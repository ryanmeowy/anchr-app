package com.anchr.core.search.domain.port;

/**
 * Domain port for OCR capability used by search.
 */
public interface SearchOcrPort {

    /**
     * Extract OCR text from image input.
     */
    String extractText(String imageInput);
}
