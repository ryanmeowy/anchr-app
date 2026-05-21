package com.anchr.core.kb.interfaces.rest.ingestion;

import com.anchr.core.auth.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.kb.application.ingestion.IngestionCapabilityService;
import com.anchr.core.kb.interfaces.rest.dto.ingestion.IngestionCapabilityDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion capability APIs.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
public class IngestionCapabilityApiController {

    private final IngestionCapabilityService ingestionCapabilityService;

    @RequireAuth
    @GetMapping("/capabilities")
    public Result<IngestionCapabilityDTO> getCapabilities() {
        return Result.success(IngestionCapabilityDTO.from(ingestionCapabilityService.getCapabilities()));
    }
}
