package com.anchr.core.search.domain.repository;

import com.anchr.core.search.domain.model.KbSegmentHit;
import com.anchr.core.search.domain.model.Segment;

import java.util.List;
import java.util.Optional;

/**
 * Repository for kb_segment retrieval and lookup inside the search domain.
 */
public interface KbSegmentRepository {

    List<KbSegmentHit> textSearch(String query, int limit);

    List<KbSegmentHit> vectorSearch(List<Float> queryVector, int topK);

    Optional<Segment> findBySegmentId(String segmentId);

    List<Segment> findNeighborChunks(String assetId, Integer pageNo, Integer chunkOrder, int window);
}
