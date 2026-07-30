package com.anchr.core.search.application.impl;

import co.elastic.clients.json.JsonData;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SegmentIndexMigrationValidationTest {

    @Test
    void validateEmbeddingShouldAcceptExpectedFiniteVector() {
        assertDoesNotThrow(() -> SegmentIndexMigrationRunner.validateEmbedding(
                "segment-1", List.of(0.1f, 0.2f, 0.3f), 3));
    }

    @Test
    void validateEmbeddingShouldRejectMissingOrWrongDimensionVector() {
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexMigrationRunner.validateEmbedding(
                        "segment-1", null, 3));
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexMigrationRunner.validateEmbedding(
                        "segment-1", List.of(0.1f, 0.2f), 3));
    }

    @Test
    void validateEmbeddingShouldRejectNonFiniteValues() {
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexMigrationRunner.validateEmbedding(
                        "segment-1", List.of(0.1f, Float.NaN, 0.3f), 3));
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexMigrationRunner.validateEmbedding(
                        "segment-1", List.of(0.1f, Float.POSITIVE_INFINITY, 0.3f), 3));
    }

    @Test
    void validateMigrationCountsShouldAllowProfileProjectionToChangeDocumentCount() {
        assertDoesNotThrow(() ->
                SegmentIndexMigrationRunner.validateMigrationCounts(10, 10, 12, 12));
        assertDoesNotThrow(() ->
                SegmentIndexMigrationRunner.validateMigrationCounts(10, 10, 8, 8));
        assertThrows(IllegalStateException.class, () ->
                SegmentIndexMigrationRunner.validateMigrationCounts(10, 9, 12, 12));
        assertThrows(IllegalStateException.class, () ->
                SegmentIndexMigrationRunner.validateMigrationCounts(10, 10, 12, 11));
    }

    @Test
    void mappingMetadataShouldContainNonSensitiveEmbeddingProfile() {
        EmbeddingProfile profile =
                new EmbeddingProfile(42L, "EMBEDDING", "model-a", 1024, "fingerprint-a");

        Map<String, JsonData> metadata =
                SegmentPhysicalIndexFactory.toMappingMetadata(profile);

        assertEquals(Set.of(
                "embeddingProfileVersion",
                "embeddingProfileFingerprint",
                "embeddingCapability",
                "embeddingModel",
                "embeddingDimension"), metadata.keySet());
        assertEquals("fingerprint-a", SegmentIndexTopologyInspector.readMetadataString(
                metadata, "embeddingProfileFingerprint"));
        assertEquals("EMBEDDING", SegmentIndexTopologyInspector.readMetadataString(
                metadata, "embeddingCapability"));
        assertEquals("model-a", SegmentIndexTopologyInspector.readMetadataString(
                metadata, "embeddingModel"));
        assertEquals(1024, SegmentIndexTopologyInspector.readMetadataInteger(
                metadata, "embeddingDimension"));
        assertEquals(1, SegmentIndexTopologyInspector.readMetadataInteger(
                metadata, "embeddingProfileVersion"));
    }

    @Test
    void rebuildShouldPresignObjectKeysButKeepStableDirectUrls() {
        SearchObjectStoragePort storage = new SearchObjectStoragePort() {
            @Override
            public String buildAiImageInput(
                    String objectKey,
                    AiInputValidity validity
            ) {
                return "signed://" + objectKey;
            }

            @Override
            public String buildDisplayImageUrl(String objectKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SignedObjectUrl buildPreviewUrl(String objectKey) {
                throw new UnsupportedOperationException();
            }
        };
        SegmentIndexMigrationRunner runner =
                new SegmentIndexMigrationRunner(null, storage, null);

        assertEquals(
                "signed://images/photo.png",
                runner.resolveRebuildImageInput("images/photo.png"));
        assertEquals(
                "https://cdn.example.test/photo.png",
                runner.resolveRebuildImageInput(
                        "https://cdn.example.test/photo.png"));
    }

}
