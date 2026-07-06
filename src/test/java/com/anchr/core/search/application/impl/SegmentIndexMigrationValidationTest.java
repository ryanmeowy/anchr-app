package com.anchr.core.search.application.impl;

import co.elastic.clients.json.JsonData;
import com.anchr.core.search.domain.model.EmbeddingProfile;
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
        assertDoesNotThrow(() -> SegmentIndexManagerImpl.validateEmbedding(
                "segment-1", List.of(0.1f, 0.2f, 0.3f), 3));
    }

    @Test
    void validateEmbeddingShouldRejectMissingOrWrongDimensionVector() {
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexManagerImpl.validateEmbedding("segment-1", null, 3));
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexManagerImpl.validateEmbedding(
                        "segment-1", List.of(0.1f, 0.2f), 3));
    }

    @Test
    void validateEmbeddingShouldRejectNonFiniteValues() {
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexManagerImpl.validateEmbedding(
                        "segment-1", List.of(0.1f, Float.NaN, 0.3f), 3));
        assertThrows(IllegalStateException.class,
                () -> SegmentIndexManagerImpl.validateEmbedding(
                        "segment-1", List.of(0.1f, Float.POSITIVE_INFINITY, 0.3f), 3));
    }

    @Test
    void validateMigrationCountsShouldRequireExactMatch() {
        assertDoesNotThrow(() ->
                SegmentIndexManagerImpl.validateMigrationCounts(10, 10, 10));
        assertThrows(IllegalStateException.class, () ->
                SegmentIndexManagerImpl.validateMigrationCounts(10, 9, 9));
        assertThrows(IllegalStateException.class, () ->
                SegmentIndexManagerImpl.validateMigrationCounts(10, 10, 9));
    }

    @Test
    void mappingMetadataShouldContainNonSensitiveEmbeddingProfile() {
        EmbeddingProfile profile =
                new EmbeddingProfile(42L, "EMBEDDING", "model-a", 1024, "fingerprint-a");

        Map<String, JsonData> metadata = SegmentIndexManagerImpl.toMappingMetadata(profile);

        assertEquals(Set.of(
                "embeddingProfileVersion",
                "embeddingProfileFingerprint",
                "embeddingCapability",
                "embeddingModel",
                "embeddingDimension"), metadata.keySet());
        assertEquals("fingerprint-a", SegmentIndexManagerImpl.readMetadataString(
                metadata, "embeddingProfileFingerprint"));
        assertEquals("EMBEDDING", SegmentIndexManagerImpl.readMetadataString(
                metadata, "embeddingCapability"));
        assertEquals("model-a", SegmentIndexManagerImpl.readMetadataString(
                metadata, "embeddingModel"));
        assertEquals(1024, SegmentIndexManagerImpl.readMetadataInteger(
                metadata, "embeddingDimension"));
        assertEquals(1, SegmentIndexManagerImpl.readMetadataInteger(
                metadata, "embeddingProfileVersion"));
    }
}
