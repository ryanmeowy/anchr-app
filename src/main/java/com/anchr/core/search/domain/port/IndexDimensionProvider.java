package com.anchr.core.search.domain.port;

import java.util.Optional;

/**
 * Provides the vector dimension for the active embedding capability.
 * Used by index management to determine ES mapping dims.
 */
public interface IndexDimensionProvider {
    Optional<Integer> getActiveEmbeddingDimension();
    Optional<String> getActiveEmbeddingModelKey();
}
