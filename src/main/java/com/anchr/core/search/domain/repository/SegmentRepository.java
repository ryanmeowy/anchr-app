package com.anchr.core.search.domain.repository;

import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.Segment;

import java.util.List;
import java.util.Optional;

/**
 * Repository for kb_segment retrieval and lookup inside the search domain.
 */
public interface SegmentRepository {

    List<SegmentHit> textSearch(String query, int limit);

    List<SegmentHit> textSearch(String query, int limit, SearchFilter filter);

    List<SegmentHit> textSearch(String query, List<String> keywords, int limit, SearchFilter filter);

    List<SegmentHit> vectorSearch(List<Float> queryVector, int topK);

    List<SegmentHit> vectorSearch(List<Float> queryVector, int topK, SearchFilter filter);

    Optional<Segment> findBySegmentId(String segmentId);

    List<Segment> findNeighborChunks(String assetId, Integer chunkOrder, int window);

    void deleteByAssetId(String assetId);
}
