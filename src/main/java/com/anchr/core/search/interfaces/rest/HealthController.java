package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.search.application.EsHealthService;
import com.anchr.core.search.interfaces.rest.dto.EsHealthDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check API for Elasticsearch.
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final EsHealthService esHealthService;

    @RequireAuth
    @GetMapping("/elasticsearch")
    public Result<EsHealthDTO> esHealth() {
        return Result.success(esHealthService.getEsHealth());
    }
}
