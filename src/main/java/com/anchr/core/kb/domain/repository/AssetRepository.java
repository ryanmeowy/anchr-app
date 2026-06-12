package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.Asset;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository boundary for document assets.
 */
public interface AssetRepository {

    void save(Asset asset);

    Optional<Asset> findActiveById(String kbId, String assetId);

    List<Asset> listActive(String kbId, int limit, int offset);

    long countActive(String kbId);

    Optional<Asset> findActiveByHash(String kbId, String fileHash);

    boolean updateStatuses(String kbId, String assetId,
                           String parseStatus, String indexStatus, String updatedBy, LocalDateTime updatedAt);

    boolean updateIngestionResult(String kbId, String assetId,
                                  String parseStatus, String indexStatus, int segmentCount,
                                  String errorCode, String errorMessage,
                                  String updatedBy, LocalDateTime updatedAt);

    boolean markDeleted(String kbId, String assetId, String updatedBy, LocalDateTime updatedAt);
}
