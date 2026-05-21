package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.DocumentAsset;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository boundary for document assets.
 */
public interface DocumentAssetRepository {

    Optional<DocumentAsset> findActiveById(String workspaceId, String kbId, String assetId);

    List<DocumentAsset> listActive(String workspaceId, String kbId, int limit, int offset);

    long countActive(String workspaceId, String kbId);

    Optional<DocumentAsset> findActiveByHash(String workspaceId, String kbId, String fileHash);

    boolean markDeleted(String workspaceId, String kbId, String assetId, String updatedBy, LocalDateTime updatedAt);
}
