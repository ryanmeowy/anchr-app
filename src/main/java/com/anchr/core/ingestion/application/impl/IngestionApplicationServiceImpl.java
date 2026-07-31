package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stable controller-facing facade for ingestion commands and queries.
 */
@Service
public class IngestionApplicationServiceImpl implements IngestionApplicationService {
    private final IngestionTaskCreateUseCase createUseCase;
    private final IngestionTaskMaintenanceUseCase maintenanceUseCase;
    private final IngestionTaskQuery taskQuery;

    public IngestionApplicationServiceImpl(
            IngestionTaskCreateUseCase createUseCase,
            IngestionTaskMaintenanceUseCase maintenanceUseCase,
            IngestionTaskQuery taskQuery) {
        this.createUseCase = createUseCase;
        this.maintenanceUseCase = maintenanceUseCase;
        this.taskQuery = taskQuery;
    }

    @Override
    public IngestionTaskCreateResult createTask(String kbId, IngestionCreateCommand command) {
        return createUseCase.create(kbId, command);
    }

    @Override
    public IngestionTask getTaskByClientRequestId(String kbId, String clientRequestId) {
        return taskQuery.getByClientRequestId(kbId, clientRequestId);
    }

    @Override
    public List<IngestionTask> listTasks(String kbId, IngestionTaskStatus status, int limit) {
        return taskQuery.list(kbId, status, limit);
    }

    @Override
    public IngestionTask getTask(String kbId, String taskId) {
        return taskQuery.get(kbId, taskId);
    }

    @Override
    public IngestionTask retryItem(String kbId, String taskId, String itemId) {
        return maintenanceUseCase.retryItem(kbId, taskId, itemId);
    }

    @Override
    public IngestionTask retryFailed(String kbId, String taskId) {
        return maintenanceUseCase.retryFailed(kbId, taskId);
    }

    @Override
    public IngestionTask createReparseTask(String kbId, String assetId) {
        return maintenanceUseCase.createReparseTask(kbId, assetId);
    }

    @Override
    public IngestionTask createReembedTask(String kbId, String assetId) {
        return maintenanceUseCase.createReembedTask(kbId, assetId);
    }
}
