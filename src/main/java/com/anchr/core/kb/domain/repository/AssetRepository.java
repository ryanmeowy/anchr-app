package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.AssetHealthStats;
import com.anchr.core.kb.domain.model.SourceTypeCount;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository boundary for document assets.
 */
public interface AssetRepository {

    void save(Asset asset);

    Optional<Asset> findActiveById(String kbId, String assetId);

    /**
     * Locks the asset row even when it is soft-deleted. Must be invoked inside a transaction.
     */
    Optional<Asset> findByIdForUpdate(String kbId, String assetId);

    List<Asset> listActive(String kbId, int limit, int offset);

    long countActive(String kbId);

    /**
     * Aggregated document/segment ingestion stats for a KB (counts by index_status
     * and segment sums). Returns an all-zero result when the KB has no active assets.
     */
    AssetHealthStats healthStats(String kbId);

    /** Count of active assets grouped by file type, ordered by count desc. */
    List<SourceTypeCount> countByFileType(String kbId);

    Optional<Asset> findActiveByHash(String kbId, String fileHash);

    int findMaxVersionNo(String kbId, String versionGroupId);

    boolean updateStatuses(String kbId, String assetId,
                           String parseStatus, String indexStatus, String updatedBy, LocalDateTime updatedAt);

    boolean updateIngestionResult(String kbId, String assetId,
                                  String parseStatus, String indexStatus, int segmentCount,
                                  int indexedSegmentCount,
                                  String errorCode, String errorMessage,
                                  String updatedBy, LocalDateTime updatedAt);

    boolean markDeleted(String kbId, String assetId, String updatedBy, LocalDateTime updatedAt);
}
