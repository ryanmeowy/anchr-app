package com.smart.vision.core.search.interfaces.rest;

import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.search.application.SegmentPreviewService;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Segment preview api.
 */
@RestController
@RequestMapping("/api/v1/preview")
@RequiredArgsConstructor
public class SegmentPreviewApiController {

    private final SegmentPreviewService segmentPreviewService;

    @GetMapping("/segments/{segmentId}")
    public Result<PreviewSegmentDTO> getSegmentPreview(@PathVariable @NotBlank String segmentId) {
        return Result.success(segmentPreviewService.getSegmentPreview(segmentId));
    }
}
