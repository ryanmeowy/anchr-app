package com.anchr.core.search.domain.port;

import com.anchr.core.search.domain.model.EmbeddingProfile;

import java.util.Optional;

/**
 * Provides an immutable snapshot of the active embedding capability.
 */
public interface EmbeddingProfileProvider {
    Optional<EmbeddingProfile> getActiveEmbeddingProfile();
}
