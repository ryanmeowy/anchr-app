package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentIndexMigrationProjectionTest {

    @Test
    @SuppressWarnings("unchecked")
    void managerShouldUseOnePlannerAcrossBatchesAndEmbedOnlyOneVisual() {
        SegmentIndexManagerImpl manager = new SegmentIndexManagerImpl(
                null,
                new SegmentIndexConfig(),
                null,
                null,
                null,
                Runnable::run,
                new SegmentIndexWriteBarrier(),
                null);
        EmbeddingProfile profile = new EmbeddingProfile(
                1L, "MULTI_EMBEDDING", "multi-model", 2, "fingerprint");
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner(profile.capability());
        List<String> calls = new ArrayList<>();
        EmbeddingSession session = (source, sourceType) -> {
            calls.add(sourceType + ":" + source);
            return List.of(0.1f, 0.2f);
        };

        List<?> first = ReflectionTestUtils.invokeMethod(
                manager,
                "prepareMigrationBatch",
                List.of(hit("ocr-1", imageOcr("ocr-1", "first"))),
                profile,
                session,
                planner);
        List<?> second = ReflectionTestUtils.invokeMethod(
                manager,
                "prepareMigrationBatch",
                List.of(hit("ocr-2", imageOcr("ocr-2", "second"))),
                profile,
                session,
                planner);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(calls).containsExactly(
                "image:https://images.example.test/photo.png");
        List<SegmentDocument> documents = new ArrayList<>();
        for (Object item : first) {
            documents.add((SegmentDocument) ReflectionTestUtils.getField(
                    item, "document"));
        }
        for (Object item : second) {
            documents.add((SegmentDocument) ReflectionTestUtils.getField(
                    item, "document"));
        }
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

    private Hit<SegmentDocument> hit(
            String id,
            SegmentDocument document
    ) {
        return Hit.of(hit -> hit
                .index("kb_segment_old")
                .id(id)
                .source(document));
    }

    private SegmentDocument imageOcr(String id, String text) {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId(id);
        document.setKbId("kb-1");
        document.setAssetId("asset-1");
        document.setIndexGeneration(3L);
        document.setAssetType("IMAGE");
        document.setSegmentType(SegmentType.IMAGE_OCR_BLOCK.name());
        document.setOcrText(text);
        document.setSourceRef("https://images.example.test/photo.png");
        return document;
    }
}
