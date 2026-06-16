package com.anchr.core.search.domain.port;

import java.util.List;

/**
 * Domain port for embedding capabilities used by search.
 */
public interface SearchEmbeddingPort {

    List<Float> embed(String source, String sourceType);
}
