package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentIndexMigrationProjectionTest {

    @Test
    void runnerShouldUseOnePlannerAcrossBatchesAndEmbedOnlyOneVisual() {
        SegmentIndexMigrationRunner runner =
                new SegmentIndexMigrationRunner(null, null, null);
        EmbeddingProfile profile = new EmbeddingProfile(
                1L, "MULTI_EMBEDDING", "multi-model", 2, "fingerprint");
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner(
                        profile.capability(), () -> "visual-1");
        List<String> calls = new ArrayList<>();
        EmbeddingSession session = (source, sourceType) -> {
            calls.add(sourceType + ":" + source);
            return List.of(0.1f, 0.2f);
        };

        List<SegmentIndexMigrationRunner.MigrationDocument> first =
                runner.prepareMigrationBatch(
                List.of(hit("ocr-1", imageOcr("ocr-1", "first", 1))),
                profile,
                session,
                planner);
        List<SegmentIndexMigrationRunner.MigrationDocument> second =
                runner.prepareMigrationBatch(
                List.of(hit("ocr-2", imageOcr("ocr-2", "second", 2))),
                profile,
                session,
                planner);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(calls).containsExactly(
                "image:https://images.example.test/photo.png");
        List<SegmentDocument> documents = new ArrayList<>();
        first.forEach(item -> documents.add(item.document()));
        second.forEach(item -> documents.add(item.document()));
        assertThat(documents).filteredOn(document ->
                        SegmentType.IMAGE_VISUAL.name().equals(
                                document.getSegmentType()))
                .singleElement()
                .satisfies(document -> assertThat(document.getEmbedding())
                        .containsExactly(0.1f, 0.2f));
        assertThat(documents).filteredOn(document ->
                        SegmentType.IMAGE_OCR_BLOCK.name().equals(
                                document.getSegmentType()))
                .hasSize(2)
                .allSatisfy(document ->
                        assertThat(document.getEmbedding()).isNull());
    }

    @Test
    void runnerShouldPreserveExistingEsIdAndSegmentIdIndependently() {
        SegmentIndexMigrationRunner runner =
                new SegmentIndexMigrationRunner(null, null, null);
        EmbeddingProfile profile = new EmbeddingProfile(
                1L, "EMBEDDING", "text-model", 2, "fingerprint");
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner(
                        profile.capability(), () -> "unused");
        SegmentDocument source = new SegmentDocument();
        source.setSegmentId("source-segment-id");
        source.setAssetId("asset-1");
        source.setAssetType("PDF");
        source.setSegmentType(SegmentType.TEXT_CHUNK.name());
        source.setContentText("text");

        List<SegmentIndexMigrationRunner.MigrationDocument> migrated =
                runner.prepareMigrationBatch(
                List.of(hit("existing-es-id", source)),
                profile,
                (EmbeddingSession) (text, type) -> List.of(0.1f, 0.2f),
                planner);

        assertThat(migrated).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("existing-es-id");
            SegmentDocument document = item.document();
            assertThat(document.getSegmentId()).isEqualTo("source-segment-id");
            assertThat(document.getChunkOrder()).isNull();
        });
    }

    private Hit<SegmentDocument> hit(
            String id,
            SegmentDocument document
    ) {
        return Hit.of(hit -> hit
                .index("kb_segment_old")
                .id(id)
                .source(document));
    }

    private SegmentDocument imageOcr(String id, String text, int chunkOrder) {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId(id);
        document.setKbId("kb-1");
        document.setAssetId("asset-1");
        document.setIndexGeneration(3L);
        document.setAssetType("IMAGE");
        document.setSegmentType(SegmentType.IMAGE_OCR_BLOCK.name());
        document.setChunkOrder(chunkOrder);
        document.setOcrText(text);
        document.setSourceRef("https://images.example.test/photo.png");
        return document;
    }
}
