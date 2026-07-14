package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.search.application.SegmentPreviewService;
import com.anchr.core.search.interfaces.rest.dto.PreviewRequestDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Segment preview api.
 */
@RestController
@RequestMapping("/api/v1/preview")
@RequiredArgsConstructor
public class PreviewController {

    private final SegmentPreviewService segmentPreviewService;

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @PostMapping("/segments/{segmentId}")
    public Result<PreviewSegmentDTO> getSegmentPreview(
            @PathVariable @NotBlank String segmentId,
            @RequestBody PreviewRequestDTO request) {
        return Result.success(segmentPreviewService.getSegmentPreview(segmentId, request));
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @PostMapping("/segments/{segmentId}/refresh")
    public Result<PreviewSegmentDTO> refreshSegmentPreview(
            @PathVariable @NotBlank String segmentId,
            @RequestBody PreviewRequestDTO request) {
        return Result.success(segmentPreviewService.refreshSegmentPreview(segmentId, request));
    }
}
