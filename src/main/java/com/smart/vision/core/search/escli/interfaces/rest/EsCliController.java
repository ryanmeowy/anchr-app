package com.smart.vision.core.search.escli.interfaces.rest;

import com.smart.vision.core.common.model.Result;
import com.smart.vision.core.auth.RequireAuth;
import com.smart.vision.core.search.escli.application.EsCliQueryService;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsClusterHealthDTO;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsClusterStatsDTO;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsDocGetDTO;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsDocSearchDTO;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsDocSearchRequestDTO;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsIndexListDTO;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsIndexMappingDTO;
import com.smart.vision.core.search.escli.interfaces.rest.dto.EsIndexStatsDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * Backend APIs used by the internal ES CLI.
 */
@RestController
@RequestMapping("/api/es")
@RequiredArgsConstructor
@Validated
public class EsCliController {

    private final EsCliQueryService esCliQueryService;

    @GetMapping("/cluster/health")
    @RequireAuth
    public Result<EsClusterHealthDTO> clusterHealth() {
        return Result.success(esCliQueryService.getClusterHealth());
    }

    @GetMapping("/cluster/stats")
    @RequireAuth
    public Result<EsClusterStatsDTO> clusterStats() {
        return Result.success(esCliQueryService.getClusterStats());
    }

    @GetMapping("/indices")
    @RequireAuth
    public Result<EsIndexListDTO> listIndices(@RequestParam(required = false) String pattern,
                                              @RequestParam(required = false)
                                              @Pattern(regexp = "^(green|yellow|red)?$", message = "status must be one of green,yellow,red") String status,
                                              @RequestParam(defaultValue = "1") @Min(value = 1, message = "page must be >= 1") int page,
                                              @RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be >= 1")
                                              @Max(value = 100, message = "size must be <= 100") int size) {
        return Result.success(esCliQueryService.listIndices(pattern, status, page, size));
    }

    @GetMapping("/indices/{index}/stats")
    @RequireAuth
    public Result<EsIndexStatsDTO> indexStats(@PathVariable String index) {
        return Result.success(esCliQueryService.getIndexStats(index));
    }

    @GetMapping("/indices/{index}/mapping")
    @RequireAuth
    public Result<EsIndexMappingDTO> indexMapping(@PathVariable String index) {
        return Result.success(esCliQueryService.getIndexMapping(index));
    }

    @GetMapping("/indices/{index}/docs/{id}")
    @RequireAuth
    public Result<EsDocGetDTO> docGet(@PathVariable String index,
                                      @PathVariable String id,
                                      @RequestParam(defaultValue = "true") boolean source) {
        return Result.success(esCliQueryService.getDocument(index, id, source));
    }

    @PostMapping("/indices/{index}/search")
    @RequireAuth
    public Result<EsDocSearchDTO> docSearch(@PathVariable String index,
                                            @Valid @RequestBody EsDocSearchRequestDTO request) {
        return Result.success(esCliQueryService.searchDocuments(index, request));
    }
}
