package com.anchr.core.ingestion.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionCapabilityDTO;
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
public class IngestionCapabilityController {

    private final IngestionCapabilityService ingestionCapabilityService;

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/capabilities")
    public Result<IngestionCapabilityDTO> getCapabilities() {
        return Result.success(IngestionCapabilityDTO.from(ingestionCapabilityService.getCapabilities()));
    }
}
