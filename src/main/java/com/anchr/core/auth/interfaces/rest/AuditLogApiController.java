package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.application.AuditLogService;
import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.auth.infrastructure.persistence.AuditLogRecord;
import com.anchr.core.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogApiController {

    private final AuditLogService auditLogService;

    @RequireAuth
    @GetMapping
    public Result<List<AuditLogRecord>> list(@RequestParam(required = false) String action,
                                             @RequestParam(required = false) String resourceType,
                                             @RequestParam(required = false) String userId,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                             @RequestParam(defaultValue = "50") int limit) {
        return Result.success(auditLogService.list(action, resourceType, userId, from, to, limit));
    }
}
