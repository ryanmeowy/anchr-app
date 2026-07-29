package com.anchr.core.ingestion.application.acl;

import com.anchr.core.activity.application.api.ActivityRecordApi;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/** Ingestion-side adapter that appends Activity only after the task transaction commits. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionActivityAcl {

    private final ActivityRecordApi activityRecordApi;

    public void recordDocumentImported(IngestionTask task) {
        try {
            ActivityRecordCommand.DocumentImported command = new ActivityRecordCommand.DocumentImported(
                    UserContextHolder.get().userId(), task.getId(), task.getKbId(), task.getStatus().name(),
                    task.getTotalCount(), task.getSuccessCount(), task.getFailureCount(), task.getRunningCount(),
                    LocalDateTime.now());
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                append(command);
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    append(command);
                }
            });
        } catch (Exception e) {
            log.warn("document activity scheduling failed, taskId={}", task == null ? null : task.getId(), e);
        }
    }

    private void append(ActivityRecordCommand.DocumentImported command) {
        try {
            activityRecordApi.recordDocumentImported(command);
        } catch (Exception e) {
            log.warn("document activity record failed, taskId={}", command.taskId(), e);
        }
    }
}
