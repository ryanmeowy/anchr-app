package com.anchr.core.search.application.impl;

import co.elastic.clients.json.JsonData;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.port.SearchEmbeddingPort.EmbeddingSession;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void rebuildEmbeddingShouldFallbackToOcrTextWhenContentTextIsMissing() throws Exception {
        SegmentDocument doc = new SegmentDocument();
        doc.setAssetType("PDF");
        doc.setOcrText("ocr content");
        AtomicReference<String> embeddedSource = new AtomicReference<>();
        AtomicReference<String> embeddedSourceType = new AtomicReference<>();

        List<Float> embedding = computeNewEmbedding(doc, textProfile(), (source, sourceType) -> {
            embeddedSource.set(source);
            embeddedSourceType.set(sourceType);
            return List.of(0.1f, 0.2f);
        }, null);

        assertEquals(List.of(0.1f, 0.2f), embedding);
        assertEquals("ocr content", embeddedSource.get());
        assertEquals("text", embeddedSourceType.get());
    }

    @Test
    void rebuildImageEmbeddingShouldFallbackToTextWhenTargetProfileDoesNotSupportImage() throws Exception {
        SegmentDocument doc = new SegmentDocument();
        doc.setAssetType("IMAGE");
        doc.setOcrText("image ocr content");
        AtomicReference<String> embeddedSource = new AtomicReference<>();
        AtomicReference<String> embeddedSourceType = new AtomicReference<>();

        computeNewEmbedding(doc, textProfile(), (source, sourceType) -> {
            embeddedSource.set(source);
            embeddedSourceType.set(sourceType);
            return List.of(0.1f, 0.2f);
        }, objectStoragePort());

        assertEquals("image ocr content", embeddedSource.get());
        assertEquals("text", embeddedSourceType.get());
    }

    @Test
    void rebuildImageEmbeddingShouldUseImageWhenTargetProfileSupportsImage() throws Exception {
        SegmentDocument doc = new SegmentDocument();
        doc.setAssetType("IMAGE");
        doc.setThumbnail("thumb-key");
        AtomicReference<String> embeddedSource = new AtomicReference<>();
        AtomicReference<String> embeddedSourceType = new AtomicReference<>();

        computeNewEmbedding(doc, multiProfile(), (source, sourceType) -> {
            embeddedSource.set(source);
            embeddedSourceType.set(sourceType);
            return List.of(0.1f, 0.2f);
        }, objectStoragePort());

        assertEquals("ai://thumb-key", embeddedSource.get());
        assertEquals("image", embeddedSourceType.get());
    }

    @SuppressWarnings("unchecked")
    private List<Float> computeNewEmbedding(
            SegmentDocument doc,
            EmbeddingProfile profile,
            EmbeddingSession embeddingSession,
            SearchObjectStoragePort objectStoragePort
    ) throws Exception {
        SegmentIndexManagerImpl manager = new SegmentIndexManagerImpl(
                null,
                null,
                null,
                null,
                objectStoragePort,
                Runnable::run,
                null,
                null);
        Method method = SegmentIndexManagerImpl.class.getDeclaredMethod(
                "computeNewEmbedding",
                SegmentDocument.class,
                String.class,
                EmbeddingProfile.class,
                EmbeddingSession.class);
        method.setAccessible(true);
        try {
            return (List<Float>) method.invoke(manager, doc, "segment-1", profile, embeddingSession);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private EmbeddingProfile textProfile() {
        return new EmbeddingProfile(42L, "EMBEDDING", "text-model", 2, "text-fingerprint");
    }

    private EmbeddingProfile multiProfile() {
        return new EmbeddingProfile(43L, "MULTI_EMBEDDING", "multi-model", 2, "multi-fingerprint");
    }

    private SearchObjectStoragePort objectStoragePort() {
        return new SearchObjectStoragePort() {
            @Override
            public String uploadFile(org.springframework.web.multipart.MultipartFile file) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String buildAiImageInput(String objectKey, AiInputValidity validity) {
                return "ai://" + objectKey;
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
    }
}
