package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.search.application.SegmentPreviewService;
import com.anchr.core.search.interfaces.rest.dto.PreviewNeighborsDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Segment preview api.
 */
@RestController
@RequestMapping("/api/v1/preview")
@RequiredArgsConstructor
public class PreviewController {

    private final SegmentPreviewService segmentPreviewService;

    @RequireAuth
    @GetMapping("/segments/{segmentId}")
    public Result<PreviewSegmentDTO> getSegmentPreview(
            @PathVariable @NotBlank String segmentId) {
        return Result.success(segmentPreviewService.getSegmentPreview(segmentId));
    }

    @RequireAuth
    @GetMapping("/segments/{segmentId}/neighbors")
    public Result<PreviewNeighborsDTO> getSegmentNeighbors(
            @PathVariable @NotBlank String segmentId,
            @RequestParam(defaultValue = "3") int before,
            @RequestParam(defaultValue = "3") int after) {
        return Result.success(segmentPreviewService.getSegmentNeighbors(segmentId, before, after));
    }

    @RequireAuth
    @PostMapping("/segments/{segmentId}/refresh")
    public Result<PreviewSegmentDTO> refreshSegmentPreview(
            @PathVariable @NotBlank String segmentId) {
        return Result.success(segmentPreviewService.refreshSegmentPreview(segmentId));
    }
}
