package com.smart.vision.core.integration.multimodal.embedding;

import java.util.List;

public interface EmbeddingBackend {

    String backendName();

    List<Float> embedText(String text);

    List<Float> embedImage(String imageInput);

    List<Float> embedImage(byte[] imageBytes, String contentType);
}
