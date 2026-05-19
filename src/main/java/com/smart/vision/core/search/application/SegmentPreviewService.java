package com.smart.vision.core.search.application;

import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;

/**
 * Application service for segment preview metadata.
 */
public interface SegmentPreviewService {

    PreviewSegmentDTO getSegmentPreview(String segmentId, String accessToken);
}
