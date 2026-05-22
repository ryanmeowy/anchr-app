package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.DocumentAsset;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository boundary for document assets.
 */
public interface DocumentAssetRepository {

    void save(DocumentAsset documentAsset);

    Optional<DocumentAsset> findActiveById(String workspaceId, String kbId, String assetId);

    List<DocumentAsset> listActive(String workspaceId, String kbId, int limit, int offset);

    long countActive(String workspaceId, String kbId);

    Optional<DocumentAsset> findActiveByHash(String workspaceId, String kbId, String fileHash);

    boolean updateStatuses(String workspaceId, String kbId, String assetId,
                           String parseStatus, String indexStatus, String updatedBy, LocalDateTime updatedAt);

    boolean markDeleted(String workspaceId, String kbId, String assetId, String updatedBy, LocalDateTime updatedAt);
}
