package com.anchr.core.search.domain.model;

import com.anchr.core.search.domain.model.EmbeddingProjection.SourceKind;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy.Profile;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingProjectionPolicyTest {

    @Test
    void textProfileShouldEmbedTextChunksFromContentText() {
        EmbeddingProjection projection = select(
                Profile.TEXT, "PDF", SegmentType.TEXT_CHUNK,
                "document text", null, null).orElseThrow();

        assertThat(projection.inputType())
                .isEqualTo(EmbeddingProjection.InputType.TEXT);
        assertThat(projection.source()).isEqualTo("document text");
        assertThat(projection.sourceKind()).isEqualTo(SourceKind.CONTENT_TEXT);
        assertThat(projection.projectionKind()).isEqualTo(SegmentType.TEXT_CHUNK);
    }

    @Test
    void textProfileShouldEmbedImageOcrAndAllowBlankOcr() {
        EmbeddingProjection projection = select(
                Profile.TEXT, "IMAGE", SegmentType.IMAGE_OCR_BLOCK,
                null, "recognized text", null).orElseThrow();

        assertThat(projection.inputType())
                .isEqualTo(EmbeddingProjection.InputType.TEXT);
        assertThat(projection.source()).isEqualTo("recognized text");
        assertThat(projection.sourceKind()).isEqualTo(SourceKind.OCR_TEXT);
        assertThat(select(
                Profile.TEXT, "IMAGE", SegmentType.IMAGE_OCR_BLOCK,
                null, " ", null)).isEmpty();
        assertThat(EmbeddingProjectionPolicy.requiresImageVisual(
                Profile.TEXT, "IMAGE")).isFalse();
    }

    @Test
    void multiProfileShouldLeaveOcrWithoutVectorAndEmbedOneVisualSource() {
        assertThat(select(
                Profile.MULTI, "IMAGE", SegmentType.IMAGE_OCR_BLOCK,
                null, "recognized text", null)).isEmpty();

        EmbeddingProjection projection = select(
                Profile.MULTI, "IMAGE", SegmentType.IMAGE_VISUAL,
                null, null, "images/photo.png").orElseThrow();

        assertThat(projection.inputType())
                .isEqualTo(EmbeddingProjection.InputType.IMAGE);
        assertThat(projection.source()).isEqualTo("images/photo.png");
        assertThat(projection.sourceKind()).isEqualTo(SourceKind.ORIGINAL_IMAGE);
        assertThat(projection.projectionKind()).isEqualTo(SegmentType.IMAGE_VISUAL);
        assertThat(EmbeddingProjectionPolicy.requiresImageVisual(
                Profile.MULTI, "IMAGE")).isTrue();
    }

    @Test
    void multiProfileShouldStillEmbedTextChunksFromContentText() {
        EmbeddingProjection projection = select(
                Profile.MULTI, "MARKDOWN", SegmentType.TEXT_CHUNK,
                "markdown content", null, null).orElseThrow();

        assertThat(projection.inputType())
                .isEqualTo(EmbeddingProjection.InputType.TEXT);
        assertThat(projection.sourceKind()).isEqualTo(SourceKind.CONTENT_TEXT);
        assertThat(projection.source()).isEqualTo("markdown content");
    }

    private Optional<EmbeddingProjection> select(
            Profile profile,
            String assetType,
            SegmentType segmentType,
            String contentText,
            String ocrText,
            String imageSource
    ) {
        return EmbeddingProjectionPolicy.select(
                profile, assetType, segmentType,
                contentText, ocrText, imageSource);
    }
}
