package com.anchr.core.ingestion.interfaces.rest;

import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reindex APIs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ingestion")
public class ReindexController {

    @RequireAuth
    @PostMapping("/reindex")
    public Result<Void> reindex() {
        // TODO: clear existing vectors and trigger re-embedding
        log.info("reindex requested");
        return Result.success(null);
    }
}
