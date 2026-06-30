package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.PreviewRequestDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewNeighborsDTO;

/**
 * Application service for segment preview metadata.
 */
public interface SegmentPreviewService {

    PreviewSegmentDTO getSegmentPreview(String segmentId, PreviewRequestDTO request);

    PreviewSegmentDTO refreshSegmentPreview(String segmentId, PreviewRequestDTO request);

    PreviewNeighborsDTO getSegmentNeighbors(String segmentId, int before, int after);
}
