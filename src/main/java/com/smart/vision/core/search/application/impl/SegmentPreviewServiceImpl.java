package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.application.SegmentPreviewService;
import com.smart.vision.core.search.domain.model.Bbox;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.repository.KbSegmentRepository;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.smart.vision.core.search.interfaces.rest.dto.SurroundingChunkDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Default segment preview service.
 */
@Service
@RequiredArgsConstructor
public class SegmentPreviewServiceImpl implements SegmentPreviewService {

    private static final int SURROUNDING_CHUNK_MAX_BYTES = 4096;
    private static final String RELATION_CURRENT = "current";

    private final KbSegmentRepository kbSegmentRepository;

    @Override
    public PreviewSegmentDTO getSegmentPreview(String segmentId) {
        if (!StringUtils.hasText(segmentId)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "segmentId cannot be blank.");
        }
        Segment segment = kbSegmentRepository.findBySegmentId(segmentId.trim())
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND, "Segment not found."));
        return toPreview(segment);
    }

    private PreviewSegmentDTO toPreview(Segment segment) {
        return PreviewSegmentDTO.builder()
                .segmentId(segment.getSegmentId())
                .assetId(segment.getAssetId())
                .assetType(toCode(segment.getAssetType()))
                .segmentType(toCode(segment.getSegmentType()))
                .fileName(resolveFileName(segment))
                .previewType(resolvePreviewType(segment))
                .previewUrl(segment.getSourceRef())
                .sourceRef(segment.getSourceRef())
                .thumbnail(segment.getThumbnail())
                .title(segment.getTitle())
                .snippet(resolveSnippet(segment))
                .ocrSummary(segment.getOcrSummary())
                .anchor(toAnchor(segment))
                .surroundingChunks(buildCurrentChunk(segment))
                .build();
    }

    private PreviewAnchorDTO toAnchor(Segment segment) {
        if (segment == null) {
            return null;
        }
        if (segment.getPageNo() == null
                && segment.getChunkOrder() == null
                && segment.getBbox() == null
                && segment.getImageWidth() == null
                && segment.getImageHeight() == null) {
            return null;
        }
        return PreviewAnchorDTO.builder()
                .pageNo(segment.getPageNo())
                .chunkOrder(segment.getChunkOrder())
                .bbox(toBbox(segment.getBbox()))
                .imageWidth(segment.getImageWidth())
                .imageHeight(segment.getImageHeight())
                .build();
    }

    private PreviewAnchorDTO.BboxDTO toBbox(Bbox source) {
        if (source == null) {
            return null;
        }
        return PreviewAnchorDTO.BboxDTO.builder()
                .x(source.getX())
                .y(source.getY())
                .width(source.getWidth())
                .height(source.getHeight())
                .unit(source.getUnit())
                .build();
    }

    private List<SurroundingChunkDTO> buildCurrentChunk(Segment segment) {
        String content = resolveSnippet(segment);
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        return List.of(SurroundingChunkDTO.builder()
                .segmentId(segment.getSegmentId())
                .chunkOrder(segment.getChunkOrder())
                .pageNo(segment.getPageNo())
                .content(truncateUtf8(content.trim(), SURROUNDING_CHUNK_MAX_BYTES))
                .relation(RELATION_CURRENT)
                .build());
    }

    private String resolvePreviewType(Segment segment) {
        if (segment.getAssetType() != null) {
            return segment.getAssetType().name();
        }
        return toCode(segment.getSegmentType());
    }

    private String resolveSnippet(Segment segment) {
        if (StringUtils.hasText(segment.getOcrText())) {
            return segment.getOcrText();
        }
        if (StringUtils.hasText(segment.getContentText())) {
            return segment.getContentText();
        }
        return segment.getTitle();
    }

    private String resolveFileName(Segment segment) {
        if (StringUtils.hasText(segment.getSourceRef())) {
            String sourceRef = segment.getSourceRef().trim();
            int queryIndex = sourceRef.indexOf('?');
            String path = queryIndex >= 0 ? sourceRef.substring(0, queryIndex) : sourceRef;
            int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (slashIndex < 0 || slashIndex == path.length() - 1) {
                return path;
            }
            return path.substring(slashIndex + 1);
        }
        return StringUtils.hasText(segment.getTitle()) ? segment.getTitle().trim() : null;
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (!StringUtils.hasText(value) || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String current = new String(Character.toChars(codePoint));
            int currentBytes = current.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + currentBytes > maxBytes) {
                break;
            }
            builder.append(current);
            usedBytes += currentBytes;
            offset += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private String toCode(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
