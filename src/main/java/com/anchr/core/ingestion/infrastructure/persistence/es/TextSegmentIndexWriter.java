package com.anchr.core.ingestion.infrastructure.persistence.es;

import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.ingestion.domain.repository.TextSegmentRepository;
import com.anchr.core.search.domain.model.AssetType;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Writes text chunks to kb_segment unified index.
 */
@Repository
@RequiredArgsConstructor
public class TextSegmentIndexWriter implements TextSegmentRepository {

    private final SegmentBulkWriter kbSegmentBulkWriter;

    @Override
    public void save(String assetId, List<Chunk> chunks) {
        if (!StringUtils.hasText(assetId) || chunks == null || chunks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Segment> segments = chunks.stream()
                .filter(java.util.Objects::nonNull)
                .filter(chunk -> StringUtils.hasText(chunk.getSegmentId()))
                .map(chunk -> Segment.builder()
                        .segmentId(chunk.getSegmentId())
                        .kbId(chunk.getKbId())
                        .assetId(chunk.getAssetId())
                        .assetType(AssetType.TEXT)
                        .segmentType(SegmentType.TEXT_CHUNK)
                        .title(chunk.getTitle())
                        .contentText(chunk.getChunkText())
                        .embedding(chunk.getEmbedding())
                        .pageNo(chunk.getPageNo())
                        .chunkOrder(chunk.getChunkOrder())
                        .sourceRef(chunk.getSourceRef())
                        .createdAt(now)
                        .build())
                .toList();
        kbSegmentBulkWriter.write(segments);
    }
}
