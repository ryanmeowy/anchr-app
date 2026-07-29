package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps ranked Retrieval candidates to the published hit model and aggregates them by Asset.
 */
@Slf4j
final class RetrievalResultAssembler {

    private SearchObjectStoragePort objectStoragePort;

    void setObjectStoragePort(SearchObjectStoragePort objectStoragePort) {
        this.objectStoragePort = objectStoragePort;
    }

    RetrievalHit toResult(SegmentRerankCandidate candidate, String keyword) {
        Segment segment = candidate.segment();
        Map<String, String> highlights =
                candidate.highlights() == null ? Map.of() : candidate.highlights();
        boolean titleHit =
                highlights.containsKey("title") || containsIgnoreCase(segment.getTitle(), keyword);
        boolean contentHit = highlights.containsKey("contentText")
                || containsIgnoreCase(segment.getContentText(), keyword);
        boolean ocrHit =
                highlights.containsKey("ocrText") || containsIgnoreCase(segment.getOcrText(), keyword);
        boolean tagHit = hasTagHit(segment, keyword, highlights);

        List<String> hitSources = new ArrayList<>();
        if (candidate.vectorHit()) {
            hitSources.add("VECTOR");
        }
        if (titleHit) {
            hitSources.add("TITLE");
        }
        if (contentHit) {
            hitSources.add(segment.getSegmentType() == SegmentType.DOCUMENT_IMAGE
                    ? "CAPTION" : "CONTENT");
        }
        if (ocrHit) {
            hitSources.add("OCR");
        }
        if (tagHit) {
            hitSources.add("TAG");
        }

        boolean visualProjection = segment.getSegmentType() == SegmentType.IMAGE_VISUAL;
        String content = resolveContent(segment);
        String snippet = visualProjection ? "" : pickSnippet(content, highlights);
        RetrievalAnchor anchor = new RetrievalAnchor(
                segment.getPageNo(), segment.getChunkOrder(), segment.getBbox(),
                segment.getImageWidth(), segment.getImageHeight());
        SearchObjectStoragePort.SignedObjectUrl imagePreview = signImagePreview(segment);
        return new RetrievalHit(
                code(segment.getSegmentType()), segment.getTitle(), content,
                resultType(segment.getSegmentType()), segment.getAssetType(), snippet,
                segment.getPageNo(), candidate.score(),
                buildExplain(segment, hitSources, candidate.vectorHit(),
                        titleHit, contentHit, ocrHit, tagHit),
                anchor, null, null, null, List.of(), segment.getSegmentId(), segment.getKbId(),
                segment.getAssetId(), segment.getSourceRef(),
                imagePreview == null ? null : imagePreview.url(),
                imagePreview == null ? null : imagePreview.expiresAt());
    }

    List<RetrievalHit> aggregateByAsset(List<RetrievalHit> rankedSegments, int limit) {
        if (rankedSegments == null || rankedSegments.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievalHit> aggregatedByAsset = new LinkedHashMap<>();
        for (RetrievalHit item : rankedSegments) {
            String groupKey = resolveAggregateKey(item);
            RetrievalTopChunk topChunk = toTopChunk(item);
            RetrievalHit aggregated = aggregatedByAsset.get(groupKey);
            if (aggregated == null) {
                aggregatedByAsset.put(groupKey, withAggregation(
                        item, item.resultType(), item.thumbnail(), item.ocrSummary(),
                        1, List.of(topChunk)));
                continue;
            }
            List<RetrievalTopChunk> topChunks = new ArrayList<>(aggregated.topChunks());
            topChunks.add(topChunk);
            int totalHits = aggregated.totalHits() == null ? 0 : aggregated.totalHits();
            String thumbnail = StringUtils.hasText(aggregated.thumbnail())
                    ? aggregated.thumbnail() : item.thumbnail();
            String ocrSummary = StringUtils.hasText(aggregated.ocrSummary())
                    ? aggregated.ocrSummary() : item.ocrSummary();
            String aggregateResultType = Objects.equals(aggregated.resultType(), item.resultType())
                    ? aggregated.resultType() : "MIXED";
            aggregatedByAsset.put(groupKey, withAggregation(
                    aggregated, aggregateResultType, thumbnail, ocrSummary,
                    totalHits + 1, topChunks));
        }
        return aggregatedByAsset.values().stream().limit(limit).toList();
    }

    RetrievalTopChunk toTopChunk(RetrievalHit segmentItem) {
        return new RetrievalTopChunk(
                segmentItem.segmentId(), segmentItem.kbId(), segmentItem.segmentType(),
                segmentItem.title(), segmentItem.content(), segmentItem.snippet(), segmentItem.explain(),
                segmentItem.score(), segmentItem.pageNo(), segmentItem.anchor(), segmentItem.sourceRef(),
                segmentItem.imagePreviewUrl(), segmentItem.imagePreviewExpiresAt(),
                segmentItem.thumbnail(), segmentItem.ocrSummary());
    }

    private RetrievalHit withAggregation(
            RetrievalHit source,
            String resultType,
            String thumbnail,
            String ocrSummary,
            int totalHits,
            List<RetrievalTopChunk> topChunks
    ) {
        return new RetrievalHit(
                source.segmentType(), source.title(), source.content(), resultType, source.assetType(),
                source.snippet(), source.pageNo(), source.score(), source.explain(), source.anchor(),
                thumbnail, ocrSummary, totalHits, topChunks, source.segmentId(), source.kbId(),
                source.assetId(), source.sourceRef(), source.imagePreviewUrl(),
                source.imagePreviewExpiresAt());
    }

    private String resolveAggregateKey(RetrievalHit item) {
        if (item == null) {
            return "";
        }
        if (StringUtils.hasText(item.assetId())) {
            return item.assetId().trim();
        }
        if (StringUtils.hasText(item.segmentId())) {
            return "__segment__" + item.segmentId().trim();
        }
        if (StringUtils.hasText(item.sourceRef())) {
            return "__source__" + item.sourceRef().trim();
        }
        return "__fallback__" + item.hashCode();
    }

    private RetrievalExplain buildExplain(
            Segment segment,
            List<String> hitSources,
            boolean vectorHit,
            boolean titleHit,
            boolean contentHit,
            boolean ocrHit,
            boolean tagHit
    ) {
        RetrievalExplain.MatchedBy matchedBy =
                new RetrievalExplain.MatchedBy(vectorHit, titleHit, contentHit, ocrHit);
        RetrievalExplain.TextSignals textSignals = null;
        RetrievalExplain.ImageSignals imageSignals = null;

        if (isTextSegment(segment)) {
            textSignals = new RetrievalExplain.TextSignals(
                    vectorHit, titleHit || contentHit || ocrHit,
                    segment.getPageNo() != null, segment.getChunkOrder() != null);
        } else if (isImageSegment(segment)) {
            imageSignals = new RetrievalExplain.ImageSignals(
                    vectorHit, ocrHit,
                    isImageCaptionSegment(segment) && (titleHit || contentHit), tagHit);
        }
        return new RetrievalExplain(hitSources, matchedBy, textSignals, imageSignals);
    }

    private String pickSnippet(String content, Map<String, String> highlights) {
        if (StringUtils.hasText(highlights.get("contentText"))) {
            return highlights.get("contentText");
        }
        if (StringUtils.hasText(highlights.get("ocrText"))) {
            return highlights.get("ocrText");
        }
        if (StringUtils.hasText(highlights.get("title"))) {
            return highlights.get("title");
        }
        return clip(content, 180);
    }

    private String clip(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String resolveContent(Segment segment) {
        if (segment == null || segment.getSegmentType() == SegmentType.IMAGE_VISUAL) {
            return "";
        }
        if (StringUtils.hasText(segment.getContentText())) {
            return segment.getContentText();
        }
        if (StringUtils.hasText(segment.getOcrText())) {
            return segment.getOcrText();
        }
        return StringUtils.hasText(segment.getTitle()) ? segment.getTitle() : "";
    }

    private boolean isTextSegment(Segment segment) {
        return segment != null && segment.getSegmentType() == SegmentType.TEXT_CHUNK;
    }

    private boolean isImageSegment(Segment segment) {
        return segment != null
                && segment.getSegmentType() != null
                && (segment.getSegmentType().name().startsWith("IMAGE_")
                || segment.getSegmentType() == SegmentType.DOCUMENT_IMAGE);
    }

    private boolean isImageCaptionSegment(Segment segment) {
        return segment != null && (segment.getSegmentType() == SegmentType.IMAGE_OCR_BLOCK
                || segment.getSegmentType() == SegmentType.DOCUMENT_IMAGE);
    }

    private boolean hasTagHit(Segment segment, String keyword, Map<String, String> highlights) {
        if (highlights.containsKey("tags")) {
            return true;
        }
        if (segment == null || !StringUtils.hasText(keyword)
                || segment.getTags() == null || segment.getTags().isEmpty()) {
            return false;
        }
        return segment.getTags().stream().anyMatch(tag -> containsIgnoreCase(tag, keyword));
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(keyword)
                && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String resultType(SegmentType segmentType) {
        if (segmentType == null) {
            return null;
        }
        return segmentType == SegmentType.IMAGE_VISUAL
                || segmentType == SegmentType.IMAGE_OCR_BLOCK
                || segmentType == SegmentType.DOCUMENT_IMAGE
                ? "IMAGE" : "TEXT";
    }

    private SearchObjectStoragePort.SignedObjectUrl signImagePreview(Segment segment) {
        if (objectStoragePort == null || segment == null
                || segment.getSegmentType() != SegmentType.DOCUMENT_IMAGE
                || !StringUtils.hasText(segment.getSourceRef())) {
            return null;
        }
        try {
            return objectStoragePort.buildPreviewUrl(segment.getSourceRef().trim());
        } catch (RuntimeException exception) {
            log.warn("embedded image preview signing failed, segmentId={}: {}",
                    segment.getSegmentId(), exception.getMessage());
            return null;
        }
    }

    private String code(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
