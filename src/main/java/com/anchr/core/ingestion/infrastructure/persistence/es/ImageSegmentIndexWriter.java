package com.anchr.core.ingestion.infrastructure.persistence.es;

import com.anchr.core.ingestion.infrastructure.persistence.es.document.IngestionImageDocument;
import com.anchr.core.ingestion.domain.model.OcrBoundingBox;
import com.anchr.core.ingestion.domain.model.OcrParagraph;
import com.anchr.core.ingestion.domain.model.OcrStructuredResult;
import com.anchr.core.search.domain.model.Bbox;
import com.anchr.core.search.domain.model.KbAssetTypeEnum;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts image ingestion result to unified kb_segment documents.
 */
@Component
@RequiredArgsConstructor
public class ImageSegmentIndexWriter {

    private static final String BBOX_WRITE_SUCCESS_METRIC = "ingestion.bbox.write_success";
    private static final String BBOX_MISSING_METRIC = "ingestion.bbox.missing";
    private static final String BBOX_OUT_OF_BOUNDS_METRIC = "ingestion.bbox.out_of_bounds";
    private static final String IMAGE_SIZE_MISSING_METRIC = "ingestion.bbox.image_size_missing";

    private final KbSegmentBulkWriter kbSegmentBulkWriter;
    private final MeterRegistry meterRegistry;

    public void write(IngestionImageDocument imageDocument) {
        if (imageDocument == null || imageDocument.getId() == null) {
            return;
        }
        List<Segment> segments = toSegments(imageDocument);
        kbSegmentBulkWriter.write(segments);
    }

    private List<Segment> toSegments(IngestionImageDocument doc) {
        String assetId = String.valueOf(doc.getId());
        long createdAt = doc.getCreateTime() == null ? System.currentTimeMillis() : doc.getCreateTime();
        String title = StringUtils.hasText(doc.getRawFilename()) ? doc.getRawFilename() : doc.getFileName();
        String ocrSummary = clip(doc.getOcrContent(), 180);
        List<Segment> segments = new ArrayList<>();

        String captionText = resolveCaptionText(doc);
        if (StringUtils.hasText(captionText)) {
            segments.add(Segment.builder()
                    .segmentId(assetId + ":caption")
                    .kbId(doc.getKbId())
                    .assetId(assetId)
                    .assetType(KbAssetTypeEnum.IMAGE)
                    .segmentType(SegmentType.IMAGE_CAPTION)
                    .title(title)
                    .contentText(captionText)
                    .embedding(doc.getImageEmbedding())
                    .sourceRef(doc.getImagePath())
                    .thumbnail(doc.getImagePath())
                    .ocrSummary(ocrSummary)
                    .tags(doc.getTags())
                    .createdAt(createdAt)
                    .build());
        }

        List<OcrParagraph> paragraphs = resolveParagraphs(doc.getStructuredOcr(), doc.getOcrContent());
        Integer imageWidth = resolveImageWidth(doc.getStructuredOcr());
        Integer imageHeight = resolveImageHeight(doc.getStructuredOcr());
        if (!paragraphs.isEmpty() && !hasValidImageSize(imageWidth, imageHeight)) {
            meterRegistry.counter(IMAGE_SIZE_MISSING_METRIC).increment();
        }
        for (OcrParagraph paragraph : paragraphs) {
            if (!StringUtils.hasText(paragraph.getText())) {
                continue;
            }
            Bbox bbox = resolveBbox(paragraph.getBbox(), imageWidth, imageHeight);
            segments.add(Segment.builder()
                    .segmentId(assetId + ":ocr:" + paragraph.getIndex())
                    .kbId(doc.getKbId())
                    .assetId(assetId)
                    .assetType(KbAssetTypeEnum.IMAGE)
                    .segmentType(SegmentType.IMAGE_OCR_BLOCK)
                    .title(title)
                    .ocrText(paragraph.getText())
                    .bbox(bbox)
                    .imageWidth(imageWidth)
                    .imageHeight(imageHeight)
                    .sourceRef(doc.getImagePath())
                    .thumbnail(doc.getImagePath())
                    .ocrSummary(ocrSummary)
                    .tags(doc.getTags())
                    .createdAt(createdAt)
                    .build());
        }
        return segments;
    }

    private List<OcrParagraph> resolveParagraphs(OcrStructuredResult structuredOcr, String ocrContent) {
        if (structuredOcr != null && structuredOcr.getParagraphs() != null && !structuredOcr.getParagraphs().isEmpty()) {
            return structuredOcr.getParagraphs();
        }
        if (!StringUtils.hasText(ocrContent)) {
            return List.of();
        }
        return List.of(OcrParagraph.builder()
                .index(0)
                .text(ocrContent)
                .build());
    }

    private Integer resolveImageWidth(OcrStructuredResult structuredOcr) {
        return structuredOcr == null ? null : structuredOcr.getImageWidth();
    }

    private Integer resolveImageHeight(OcrStructuredResult structuredOcr) {
        return structuredOcr == null ? null : structuredOcr.getImageHeight();
    }

    private Bbox resolveBbox(OcrBoundingBox box, Integer imageWidth, Integer imageHeight) {
        if (box == null || !box.isValid()) {
            meterRegistry.counter(BBOX_MISSING_METRIC).increment();
            return null;
        }
        if (hasValidImageSize(imageWidth, imageHeight) && isOutOfBounds(box, imageWidth, imageHeight)) {
            meterRegistry.counter(BBOX_OUT_OF_BOUNDS_METRIC).increment();
            return null;
        }
        meterRegistry.counter(BBOX_WRITE_SUCCESS_METRIC).increment();
        return Bbox.builder()
                .x(box.getX())
                .y(box.getY())
                .width(box.getWidth())
                .height(box.getHeight())
                .unit(box.getUnit())
                .build();
    }

    private boolean isOutOfBounds(OcrBoundingBox box, int imageWidth, int imageHeight) {
        return box.getX() < 0
                || box.getY() < 0
                || box.getX() + box.getWidth() > imageWidth
                || box.getY() + box.getHeight() > imageHeight;
    }

    private boolean hasValidImageSize(Integer imageWidth, Integer imageHeight) {
        return imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0;
    }

    private String resolveCaptionText(IngestionImageDocument doc) {
        if (StringUtils.hasText(doc.getFileName())) {
            return doc.getFileName();
        }
        if (StringUtils.hasText(doc.getRawFilename())) {
            return doc.getRawFilename();
        }
        return null;
    }

    private String clip(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }
}
