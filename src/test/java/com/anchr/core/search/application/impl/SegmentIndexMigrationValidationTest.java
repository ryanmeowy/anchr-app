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
    void validateMigrationCountsShouldAllowProfileProjectionToChangeDocumentCount() {
        assertDoesNotThrow(() ->
                SegmentIndexManagerImpl.validateMigrationCounts(10, 10, 12, 12));
        assertDoesNotThrow(() ->
                SegmentIndexManagerImpl.validateMigrationCounts(10, 10, 8, 8));
        assertThrows(IllegalStateException.class, () ->
                SegmentIndexManagerImpl.validateMigrationCounts(10, 9, 12, 12));
        assertThrows(IllegalStateException.class, () ->
                SegmentIndexManagerImpl.validateMigrationCounts(10, 10, 12, 11));
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

    @Test
    void rebuildShouldPresignObjectKeysButKeepStableDirectUrls() {
        SearchObjectStoragePort storage = new SearchObjectStoragePort() {
            @Override
            public String uploadFile(
                    org.springframework.web.multipart.MultipartFile file) {
                throw new UnsupportedOperationException();
            }

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
        SegmentIndexManagerImpl manager = new SegmentIndexManagerImpl(
                null, null, null, null, storage,
                Runnable::run, null, null);

        assertEquals(
                "signed://images/photo.png",
                manager.resolveRebuildImageInput("images/photo.png"));
        assertEquals(
                "https://cdn.example.test/photo.png",
                manager.resolveRebuildImageInput(
                        "https://cdn.example.test/photo.png"));
    }

}
