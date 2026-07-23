package com.anchr.core.ingestion.application.artifact;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Versioned durable representation of chunks after embedding and before indexing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestionEmbeddingArtifact(
        String artifactType,
        int version,
        String taskId,
        String itemId,
        String kbId,
        String assetId,
        int parseAttempt,
        long executionEpoch,
        int stageAttempt,
        String requestId,
        String sourceRevision,
        String parseResultObjectKey,
        Instant createdAt,
        List<ChunkPayload> chunks
) {

    public IngestionEmbeddingArtifact {
        chunks = chunks == null ? null : List.copyOf(chunks);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkPayload(
            String segmentId,
            String kbId,
            String assetId,
            String title,
            Integer pageNo,
            String chunkText,
            String ocrText,
            Integer chunkOrder,
            String sourceRef,
            List<Float> embedding,
            List<BboxPayload> bboxInfos
    ) {
        public ChunkPayload {
            embedding = embedding == null ? null : List.copyOf(embedding);
            bboxInfos = bboxInfos == null ? null : List.copyOf(bboxInfos);
        }

        static ChunkPayload fromDomain(Chunk chunk) {
            List<BboxPayload> bboxes = chunk.getBboxInfos() == null
                    ? null
                    : chunk.getBboxInfos().stream().map(BboxPayload::fromDomain).toList();
            return new ChunkPayload(
                    chunk.getSegmentId(),
                    chunk.getKbId(),
                    chunk.getAssetId(),
                    chunk.getTitle(),
                    chunk.getPageNo(),
                    chunk.getChunkText(),
                    chunk.getOcrText(),
                    chunk.getChunkOrder(),
                    chunk.getSourceRef(),
                    chunk.getEmbedding(),
                    bboxes);
        }

        Chunk toDomain() {
            List<BboxInfo> bboxes = bboxInfos == null
                    ? null
                    : bboxInfos.stream().map(BboxPayload::toDomain).toList();
            return Chunk.builder()
                    .segmentId(segmentId)
                    .kbId(kbId)
                    .assetId(assetId)
                    .title(title)
                    .pageNo(pageNo)
                    .chunkText(chunkText)
                    .ocrText(ocrText)
                    .chunkOrder(chunkOrder)
                    .sourceRef(sourceRef)
                    .embedding(embedding)
                    .bboxInfos(bboxes)
                    .build();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BboxPayload(
            int pageNo,
            double l,
            double t,
            double r,
            double b,
            String coordOrigin
    ) {
        static BboxPayload fromDomain(BboxInfo bboxInfo) {
            if (bboxInfo == null || bboxInfo.getBbox() == null) {
                throw new IllegalArgumentException("Chunk bbox must include coordinates.");
            }
            BboxInfo.Bbox bbox = bboxInfo.getBbox();
            return new BboxPayload(
                    bboxInfo.getPageNo(),
                    bbox.getL(),
                    bbox.getT(),
                    bbox.getR(),
                    bbox.getB(),
                    bbox.getCoordOrigin());
        }

        BboxInfo toDomain() {
            return BboxInfo.builder()
                    .pageNo(pageNo)
                    .bbox(BboxInfo.Bbox.builder()
                            .l(l)
                            .t(t)
                            .r(r)
                            .b(b)
                            .coordOrigin(coordOrigin)
                            .build())
                    .build();
        }
    }
}
