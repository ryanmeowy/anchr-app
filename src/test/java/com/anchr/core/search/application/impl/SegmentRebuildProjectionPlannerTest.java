package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy.Profile;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentRebuildProjectionPlannerTest {

    @Test
    void ingestionPolicyAndRebuildShouldSelectTheSameInputTypeAndSourceKind() {
        for (Profile profile : Profile.values()) {
            String capability = profile == Profile.MULTI
                    ? "MULTI_EMBEDDING" : "EMBEDDING";
            SegmentDocument text = textChunk();
            EmbeddingProjection ingestionProjection =
                    EmbeddingProjectionPolicy.select(
                            profile,
                            text.getAssetType(),
                            SegmentType.TEXT_CHUNK,
                            text.getContentText(),
                            null,
                            null).orElseThrow();
            EmbeddingProjection rebuildProjection =
                    new SegmentRebuildProjectionPlanner(capability)
                            .plan(text.getSegmentId(), text)
                            .getFirst()
                            .projection();

            assertSameInput(ingestionProjection, rebuildProjection);
        }

        SegmentDocument imageForText = imageOcr(
                "ocr-text", 3L, "recognized text", null);
        EmbeddingProjection ingestionOcr =
                EmbeddingProjectionPolicy.select(
                        Profile.TEXT,
                        imageForText.getAssetType(),
                        SegmentType.IMAGE_OCR_BLOCK,
                        null,
                        imageForText.getOcrText(),
                        null).orElseThrow();
        EmbeddingProjection rebuildOcr =
                new SegmentRebuildProjectionPlanner("EMBEDDING")
                        .plan(imageForText.getSegmentId(), imageForText)
                        .getFirst()
                        .projection();

        assertSameInput(ingestionOcr, rebuildOcr);

        SegmentDocument imageForMulti = imageOcr(
                "ocr-multi", 3L, "recognized text", null);
        assertThat(EmbeddingProjectionPolicy.select(
                Profile.MULTI,
                imageForMulti.getAssetType(),
                SegmentType.IMAGE_OCR_BLOCK,
                null,
                imageForMulti.getOcrText(),
                null)).isEmpty();

        List<SegmentRebuildProjectionPlanner.PlannedDocument> multiRebuild =
                new SegmentRebuildProjectionPlanner("MULTI_EMBEDDING")
                        .plan(imageForMulti.getSegmentId(), imageForMulti);
        assertThat(multiRebuild)
                .filteredOn(item -> item.document().getSegmentType().equals(
                        SegmentType.IMAGE_OCR_BLOCK.name()))
                .singleElement()
                .satisfies(item -> assertThat(item.projection()).isNull());

        EmbeddingProjection ingestionVisual =
                EmbeddingProjectionPolicy.select(
                        Profile.MULTI,
                        imageForMulti.getAssetType(),
                        SegmentType.IMAGE_VISUAL,
                        null,
                        null,
                        imageForMulti.getSourceRef()).orElseThrow();
        EmbeddingProjection rebuildVisual = multiRebuild.stream()
                .filter(item -> item.document().getSegmentType().equals(
                        SegmentType.IMAGE_VISUAL.name()))
                .findFirst()
                .orElseThrow()
                .projection();

        assertSameInput(ingestionVisual, rebuildVisual);
    }

    @Test
    void multiTargetShouldKeepOcrWithoutVectorsAndCreateOneVisualPerGeneration() {
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner("MULTI_EMBEDDING");

        List<SegmentRebuildProjectionPlanner.PlannedDocument> first =
                planner.plan("ocr-1", imageOcr("ocr-1", 3L, "first", List.of(9f)));
        List<SegmentRebuildProjectionPlanner.PlannedDocument> second =
                planner.plan("ocr-2", imageOcr("ocr-2", 3L, "second", List.of(8f)));

        assertThat(first).hasSize(2);
        assertThat(first).filteredOn(item ->
                        item.document().getSegmentType().equals(
                                SegmentType.IMAGE_OCR_BLOCK.name()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.projection()).isNull();
                    assertThat(item.document().getEmbedding()).isNull();
                    assertThat(item.document().getOcrText()).isEqualTo("first");
                });
        assertThat(first).filteredOn(item ->
                        item.document().getSegmentType().equals(
                                SegmentType.IMAGE_VISUAL.name()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.projection().inputType())
                            .isEqualTo(EmbeddingProjection.InputType.IMAGE);
                    assertThat(item.projection().source())
                            .isEqualTo("images/photo.png");
                    assertThat(item.document().getOcrText()).isNull();
                    assertThat(item.document().getChunkOrder()).isNull();
                });
        assertThat(second).singleElement().satisfies(item -> {
            assertThat(item.document().getSegmentType())
                    .isEqualTo(SegmentType.IMAGE_OCR_BLOCK.name());
            assertThat(item.projection()).isNull();
        });
    }

    @Test
    void multiTargetShouldCreateOneVisualForEachAssetGeneration() {
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner("MULTI_EMBEDDING");

        List<SegmentRebuildProjectionPlanner.PlannedDocument> firstGeneration =
                planner.plan("ocr-1", imageOcr("ocr-1", 1L, "one", null));
        List<SegmentRebuildProjectionPlanner.PlannedDocument> secondGeneration =
                planner.plan("ocr-2", imageOcr("ocr-2", 2L, "two", null));

        assertThat(firstGeneration).hasSize(2);
        assertThat(secondGeneration).hasSize(2);
        assertThat(firstGeneration.get(1).id())
                .isNotEqualTo(secondGeneration.get(1).id());
    }

    @Test
    void existingVisualShouldNotBeDuplicated() {
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner("MULTI_EMBEDDING");
        SegmentDocument existingVisual = imageOcr(
                "visual-old", 3L, null, List.of(1f));
        existingVisual.setSegmentType(SegmentType.IMAGE_VISUAL.name());

        List<SegmentRebuildProjectionPlanner.PlannedDocument> visual =
                planner.plan("visual-old", existingVisual);
        List<SegmentRebuildProjectionPlanner.PlannedDocument> ocr =
                planner.plan("ocr-1", imageOcr("ocr-1", 3L, "ocr", List.of(2f)));

        assertThat(visual).singleElement().satisfies(item -> {
                assertThat(item.document().getSegmentType())
                        .isEqualTo(SegmentType.IMAGE_VISUAL.name());
                assertThat(item.document().getEmbedding()).isNull();
                assertThat(item.projection().inputType())
                        .isEqualTo(EmbeddingProjection.InputType.IMAGE);
                assertThat(item.projection().source())
                        .isEqualTo("images/photo.png");
        });
        assertThat(ocr).singleElement().satisfies(item ->
                assertThat(item.document().getSegmentType())
                        .isEqualTo(SegmentType.IMAGE_OCR_BLOCK.name()));
    }

    @Test
    void ordinaryTextShouldUseContentTextForBothTargetProfiles() {
        for (String capability : List.of("EMBEDDING", "MULTI_EMBEDDING")) {
            SegmentDocument source = new SegmentDocument();
            source.setSegmentId("text-1");
            source.setAssetId("asset-text");
            source.setAssetType("PDF");
            source.setSegmentType(SegmentType.TEXT_CHUNK.name());
            source.setContentText("document body");
            source.setEmbedding(List.of(9f));

            var planned = new SegmentRebuildProjectionPlanner(capability)
                    .plan("text-1", source);

            assertThat(planned).singleElement().satisfies(item -> {
                assertThat(item.projection().inputType())
                        .isEqualTo(EmbeddingProjection.InputType.TEXT);
                assertThat(item.projection().source())
                        .isEqualTo("document body");
                assertThat(item.document().getEmbedding()).isNull();
            });
        }
    }

    @Test
    void textTargetShouldDropVisualAndEmbedOnlyNonBlankOcr() {
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner("EMBEDDING");
        SegmentDocument visual = imageOcr("visual", 3L, null, List.of(1f));
        visual.setSegmentType(SegmentType.IMAGE_VISUAL.name());

        List<SegmentRebuildProjectionPlanner.PlannedDocument> textOcr =
                planner.plan("ocr-1", imageOcr("ocr-1", 3L, "ocr text", List.of(2f)));
        List<SegmentRebuildProjectionPlanner.PlannedDocument> blankOcr =
                planner.plan("ocr-2", imageOcr("ocr-2", 3L, " ", List.of(3f)));

        assertThat(planner.plan("visual", visual)).isEmpty();
        assertThat(textOcr).singleElement().satisfies(item -> {
            assertThat(item.projection().inputType())
                    .isEqualTo(EmbeddingProjection.InputType.TEXT);
            assertThat(item.projection().source()).isEqualTo("ocr text");
            assertThat(item.document().getEmbedding()).isNull();
        });
        assertThat(blankOcr).singleElement().satisfies(item -> {
            assertThat(item.projection()).isNull();
            assertThat(item.document().getEmbedding()).isNull();
        });
    }

    @Test
    void multiTargetShouldFailInsteadOfEmbeddingAnUnrelatedThumbnail() {
        SegmentRebuildProjectionPlanner planner =
                new SegmentRebuildProjectionPlanner("MULTI_EMBEDDING");
        SegmentDocument source = imageOcr(
                "ocr-1", 3L, "ocr", null);
        source.setSourceRef(null);
        source.setThumbnail("thumbnails/photo.png");

        assertThatThrownBy(() -> planner.plan("ocr-1", source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no stable original image source");
    }

    private SegmentDocument imageOcr(
            String segmentId,
            long generation,
            String ocrText,
            List<Float> embedding
    ) {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId(segmentId);
        document.setKbId("kb-1");
        document.setAssetId("asset-1");
        document.setIndexGeneration(generation);
        document.setAssetType("IMAGE");
        document.setSegmentType(SegmentType.IMAGE_OCR_BLOCK.name());
        document.setTitle("photo");
        document.setOcrText(ocrText);
        document.setChunkOrder(1);
        document.setSourceRef("images/photo.png");
        document.setEmbedding(embedding);
        return document;
    }

    private SegmentDocument textChunk() {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId("text-1");
        document.setKbId("kb-1");
        document.setAssetId("asset-text");
        document.setIndexGeneration(3L);
        document.setAssetType("PDF");
        document.setSegmentType(SegmentType.TEXT_CHUNK.name());
        document.setContentText("document body");
        document.setSourceRef("documents/file.pdf");
        return document;
    }

    private void assertSameInput(
            EmbeddingProjection ingestion,
            EmbeddingProjection rebuild
    ) {
        assertThat(rebuild.inputType()).isEqualTo(ingestion.inputType());
        assertThat(rebuild.sourceKind()).isEqualTo(ingestion.sourceKind());
        assertThat(rebuild.projectionKind()).isEqualTo(ingestion.projectionKind());
    }
}
