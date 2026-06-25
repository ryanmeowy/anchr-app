package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewNeighborsDTO;

/**
 * Application service for segment preview metadata.
 */
public interface SegmentPreviewService {

    PreviewSegmentDTO getSegmentPreview(String segmentId);

    PreviewSegmentDTO refreshSegmentPreview(String segmentId);

    PreviewNeighborsDTO getSegmentNeighbors(String segmentId, int before, int after);
}
