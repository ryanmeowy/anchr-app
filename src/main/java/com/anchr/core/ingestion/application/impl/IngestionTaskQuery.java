package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.KnowledgeBaseService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
final class IngestionTaskQuery {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final String CLIENT_REQUEST_ID_PATTERN = "[A-Za-z0-9._:-]+";

    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestionTaskRepository ingestionTaskRepository;

    IngestionTaskQuery(KnowledgeBaseService knowledgeBaseService,
                       IngestionTaskRepository ingestionTaskRepository) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestionTaskRepository = ingestionTaskRepository;
    }

    List<IngestionTask> list(String kbId, IngestionTaskStatus status, int limit) {
        knowledgeBaseService.get(kbId);
        return ingestionTaskRepository.list(kbId, status, normalizeLimit(limit));
    }

    IngestionTask get(String kbId, String taskId) {
        knowledgeBaseService.get(kbId);
        return ingestionTaskRepository.findById(kbId, requireText(taskId, "taskId"))
                .orElseThrow(() -> new BusinessException(ApiError.INGESTION_TASK_NOT_FOUND));
    }

    IngestionTask getByClientRequestId(String kbId, String clientRequestId) {
        String normalizedKbId = requireText(kbId, "kbId");
        String normalizedClientRequestId = requireClientRequestId(clientRequestId);
        RequestUserContext context = UserContextHolder.get();
        // This endpoint recovers acceptance, not active KB content. The creator scope plus exact KB
        // match authorizes the lookup even after the KB has been archived.
        return ingestionTaskRepository.findByClientRequestId(context.userId(), normalizedClientRequestId)
                .filter(task -> normalizedKbId.equals(task.getKbId()))
                .orElseThrow(() -> new BusinessException(ApiError.INGESTION_TASK_NOT_FOUND));
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String requireClientRequestId(String clientRequestId) {
        String normalized = requireText(clientRequestId, "clientRequestId");
        if (normalized.length() > 128) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST, "clientRequestId length must be <= 128.");
        }
        if (!normalized.matches(CLIENT_REQUEST_ID_PATTERN)) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST, "clientRequestId contains unsupported characters.");
        }
        return normalized;
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, fieldName + " cannot be blank.");
        }
        return value.trim();
    }
}
