package com.anchr.core.search.domain.port;

import java.util.List;

/**
 * Domain port for embedding capabilities used by search.
 */
public interface SearchEmbeddingPort {

    /**
     * Embed text into vector.
     */
    List<Float> embedText(String text);

    /**
     * Embed image input (for example URL) into vector.
     */
    List<Float> embedImage(String imageInput);

    /**
     * Embed raw image bytes into vector.
     */
    List<Float> embedImage(byte[] imageBytes, String contentType);
}
