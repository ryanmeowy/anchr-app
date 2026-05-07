package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.application.SegmentPreviewService;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.repository.KbSegmentRepository;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Default segment preview service.
 */
@Service
@RequiredArgsConstructor
public class SegmentPreviewServiceImpl implements SegmentPreviewService {

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
                .previewType(resolvePreviewType(segment))
                .previewUrl(segment.getSourceRef())
                .sourceRef(segment.getSourceRef())
                .thumbnail(segment.getThumbnail())
                .title(segment.getTitle())
                .snippet(resolveSnippet(segment))
                .ocrSummary(segment.getOcrSummary())
                .anchor(PreviewSegmentDTO.Anchor.builder()
                        .pageNo(segment.getPageNo())
                        .chunkOrder(segment.getChunkOrder())
                        .bbox(segment.getBbox())
                        .imageWidth(segment.getImageWidth())
                        .imageHeight(segment.getImageHeight())
                        .build())
                .build();
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

    private String toCode(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
