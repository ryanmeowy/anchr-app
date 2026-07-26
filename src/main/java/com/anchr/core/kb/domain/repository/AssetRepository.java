package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.AssetHealthStats;
import com.anchr.core.kb.domain.model.DocumentAvailabilityStatus;
import com.anchr.core.kb.domain.model.SourceTypeCount;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * Returns active logical index generations for non-deleted assets.
     * Missing asset ids are intentionally omitted so callers can fail closed.
     */
    Map<String, Long> findActiveIndexGenerations(Collection<String> assetIds);

    default List<Asset> listActive(
            String kbId, String keyword, String fileType, int limit, int offset
    ) {
        return listActive(kbId, keyword, fileType, null, limit, offset);
    }

    List<Asset> listActive(
            String kbId,
            String keyword,
            String fileType,
            DocumentAvailabilityStatus availabilityStatus,
            int limit,
            int offset
    );

    default long countActive(String kbId, String keyword, String fileType) {
        return countActive(kbId, keyword, fileType, null);
    }

    long countActive(String kbId, String keyword, String fileType,
                     DocumentAvailabilityStatus availabilityStatus);

    default long sumActiveSegments(String kbId, String keyword, String fileType) {
        return sumActiveSegments(kbId, keyword, fileType, null);
    }

    long sumActiveSegments(String kbId, String keyword, String fileType,
                           DocumentAvailabilityStatus availabilityStatus);

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

    boolean activateIndexGeneration(String kbId, String assetId,
                                    long expectedActiveGeneration, long targetGeneration,
                                    String parseStatus, String indexStatus, int segmentCount,
                                    int indexedSegmentCount,
                                    String updatedBy, LocalDateTime updatedAt);

    boolean markDeleted(String kbId, String assetId, String updatedBy, LocalDateTime updatedAt);
}
