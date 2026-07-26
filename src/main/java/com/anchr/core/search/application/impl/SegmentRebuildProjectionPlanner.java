package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.EmbeddingProjection;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy;
import com.anchr.core.search.domain.model.EmbeddingProjectionPolicy.Profile;
import com.anchr.core.search.domain.model.SegmentIdentity;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Changes existing index documents into the segment shape required by a target profile.
 */
final class SegmentRebuildProjectionPlanner {

    private final Profile targetProfile;
    private final Set<AssetGenerationKey> plannedVisuals = new HashSet<>();

    SegmentRebuildProjectionPlanner(String targetCapability) {
        this.targetProfile = Profile.fromCapability(targetCapability);
    }

    List<PlannedDocument> plan(String documentId, SegmentDocument source) {
        if (source == null) {
            throw new IllegalArgumentException("Rebuild source document cannot be null.");
        }
        boolean image = "IMAGE".equalsIgnoreCase(source.getAssetType());
        SegmentType sourceType = sourceType(source, image);
        if (sourceType == SegmentType.DOCUMENT_IMAGE) {
            source.setSegmentType(SegmentType.DOCUMENT_IMAGE.name());
            return List.of(planDocument(documentId, source));
        }
        if (!image) {
            if (sourceType == SegmentType.IMAGE_VISUAL) {
                return List.of();
            }
            source.setSegmentType(SegmentType.TEXT_CHUNK.name());
            return List.of(planDocument(documentId, source));
        }

        if (targetProfile == Profile.TEXT) {
            if (sourceType == SegmentType.IMAGE_VISUAL) {
                return List.of();
            }
            source.setSegmentType(SegmentType.IMAGE_OCR_BLOCK.name());
            return List.of(planDocument(documentId, source));
        }

        AssetGenerationKey key = assetGenerationKey(source);
        List<PlannedDocument> result = new ArrayList<>(2);
        if (sourceType != SegmentType.IMAGE_VISUAL) {
            source.setSegmentType(SegmentType.IMAGE_OCR_BLOCK.name());
            result.add(planDocument(documentId, source));
        }
        if (plannedVisuals.add(key)) {
            SegmentDocument visual = imageVisual(source, key);
            EmbeddingProjection projection = EmbeddingProjectionPolicy.select(
                            targetProfile,
                            visual.getAssetType(),
                            SegmentType.IMAGE_VISUAL,
                            null,
                            null,
                            visual.getSourceRef())
                    .orElseThrow(() -> new IllegalStateException(
                            "Rebuild IMAGE asset " + key.assetId()
                                    + " has no stable original image source."));
            result.add(new PlannedDocument(
                    visual.getSegmentId(), visual, projection));
        }
        return result;
    }

    private PlannedDocument planDocument(
            String documentId,
            SegmentDocument document
    ) {
        SegmentType segmentType = SegmentType.valueOf(document.getSegmentType());
        EmbeddingProjection projection = EmbeddingProjectionPolicy.select(
                        targetProfile,
                        document.getAssetType(),
                        segmentType,
                        document.getContentText(),
                        document.getOcrText(),
                        segmentType == SegmentType.DOCUMENT_IMAGE
                                ? document.getSourceRef() : null)
                .orElse(null);
        document.setEmbedding(null);
        return new PlannedDocument(documentId, document, projection);
    }

    private SegmentType sourceType(SegmentDocument source, boolean image) {
        if (!StringUtils.hasText(source.getSegmentType())) {
            return image ? SegmentType.IMAGE_OCR_BLOCK : SegmentType.TEXT_CHUNK;
        }
        try {
            return SegmentType.valueOf(
                    source.getSegmentType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return image ? SegmentType.IMAGE_OCR_BLOCK : SegmentType.TEXT_CHUNK;
        }
    }

    private AssetGenerationKey assetGenerationKey(SegmentDocument source) {
        if (!StringUtils.hasText(source.getAssetId())) {
            throw new IllegalStateException(
                    "Rebuild IMAGE document has no assetId.");
        }
        return new AssetGenerationKey(
                source.getAssetId().trim(),
                source.getIndexGeneration() == null
                        ? 0L : source.getIndexGeneration());
    }

    private SegmentDocument imageVisual(
            SegmentDocument source,
            AssetGenerationKey key
    ) {
        String stableSource = StringUtils.hasText(source.getSourceRef())
                ? source.getSourceRef().trim()
                : null;
        SegmentDocument visual = new SegmentDocument();
        visual.setSegmentId(SegmentIdentity.imageVisual(
                key.assetId(), key.indexGeneration()));
        visual.setKbId(source.getKbId());
        visual.setAssetId(key.assetId());
        visual.setIndexGeneration(key.indexGeneration());
        visual.setAssetType(source.getAssetType());
        visual.setSegmentType(SegmentType.IMAGE_VISUAL.name());
        visual.setTitle(source.getTitle());
        visual.setImageWidth(source.getImageWidth());
        visual.setImageHeight(source.getImageHeight());
        visual.setSourceRef(stableSource);
        visual.setThumbnail(source.getThumbnail());
        visual.setOcrSummary(source.getOcrSummary());
        visual.setTags(source.getTags());
        visual.setCreatedAt(source.getCreatedAt());
        return visual;
    }

    record PlannedDocument(
            String id,
            SegmentDocument document,
            EmbeddingProjection projection
    ) {
    }

    private record AssetGenerationKey(String assetId, long indexGeneration) {
    }
}
