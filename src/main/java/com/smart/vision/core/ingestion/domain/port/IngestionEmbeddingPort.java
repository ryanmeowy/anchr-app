package com.smart.vision.core.ingestion.domain.port;

import java.util.List;

/**
 * Domain port for embedding capability in ingestion.
 */
public interface IngestionEmbeddingPort {

    /**
     * Embed image input to vector.
     *
     * @param imageInput image url/data input
     * @return image embedding
     */
    List<Float> embedImage(String imageInput);

    /**
     * Embed plain text input to vector.
     *
     * @param text text content
     * @return text embedding
     */
    List<Float> embedText(String text);
}
