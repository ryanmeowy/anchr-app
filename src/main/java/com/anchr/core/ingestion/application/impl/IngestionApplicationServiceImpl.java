package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionActivityAcl;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            KnowledgeBaseService knowledgeBaseService,
            AssetRepository assetRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            IngestionTaskRepository ingestionTaskRepository,
            IngestionCapabilityService ingestionCapabilityService,
            IdGen idGen,
            IngestionActivityAcl ingestionActivityAcl,
            IngestionTaskProcessor ingestionTaskProcessor,
            IngestionCreateTransactionRunner transactionRunner) {
        IngestionTaskFactory taskFactory = new IngestionTaskFactory();
        this.taskQuery = new IngestionTaskQuery(
                knowledgeBaseService, ingestionTaskRepository);
        this.createUseCase = new IngestionTaskCreateUseCase(
                knowledgeBaseService,
                assetRepository,
                knowledgeBaseRepository,
                ingestionTaskRepository,
                ingestionCapabilityService,
                idGen,
                ingestionActivityAcl,
                ingestionTaskProcessor,
                transactionRunner,
                taskQuery,
                taskFactory);
        this.maintenanceUseCase = new IngestionTaskMaintenanceUseCase(
                knowledgeBaseService,
                assetRepository,
                ingestionTaskRepository,
                idGen,
                ingestionActivityAcl,
                ingestionTaskProcessor,
                taskQuery,
                taskFactory);
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
    @Transactional
    public IngestionTask retryItem(String kbId, String taskId, String itemId) {
        return maintenanceUseCase.retryItem(kbId, taskId, itemId);
    }

    @Override
    @Transactional
    public IngestionTask retryFailed(String kbId, String taskId) {
        return maintenanceUseCase.retryFailed(kbId, taskId);
    }

    @Override
    @Transactional
    public IngestionTask createReparseTask(String kbId, String assetId) {
        return maintenanceUseCase.createReparseTask(kbId, assetId);
    }

    @Override
    @Transactional
    public IngestionTask createReembedTask(String kbId, String assetId) {
        return maintenanceUseCase.createReembedTask(kbId, assetId);
    }
}
