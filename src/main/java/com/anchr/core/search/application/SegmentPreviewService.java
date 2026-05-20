package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;

/**
 * Application service for segment preview metadata.
 */
public interface SegmentPreviewService {

    PreviewSegmentDTO getSegmentPreview(String segmentId, String accessToken);
}
