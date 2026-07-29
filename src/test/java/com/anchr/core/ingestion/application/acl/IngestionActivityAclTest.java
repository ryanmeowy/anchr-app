package com.anchr.core.ingestion.application.acl;

import com.anchr.core.activity.application.api.ActivityRecordApi;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IngestionActivityAclTest {

    private final ActivityRecordApi activityRecordApi = mock(ActivityRecordApi.class);
    private final IngestionActivityAcl acl = new IngestionActivityAcl(activityRecordApi);

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        UserContextHolder.clear();
    }

    @Test
    void appendsOnlyAfterCommitUsingTheCapturedUserSnapshot() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        TransactionSynchronizationManager.initSynchronization();

        acl.recordDocumentImported(task());
        verify(activityRecordApi, never()).recordDocumentImported(org.mockito.ArgumentMatchers.any());
        UserContextHolder.clear();

        TransactionSynchronizationUtils.triggerAfterCommit();

        ArgumentCaptor<ActivityRecordCommand.DocumentImported> captor =
                ArgumentCaptor.forClass(ActivityRecordCommand.DocumentImported.class);
        verify(activityRecordApi).recordDocumentImported(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo("user-a");
        assertThat(captor.getValue().taskId()).isEqualTo("task-1");
        assertThat(captor.getValue().totalCount()).isEqualTo(4);
    }

    @Test
    void rollbackDoesNotAppendActivity() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        TransactionSynchronizationManager.initSynchronization();

        acl.recordDocumentImported(task());
        TransactionSynchronizationManager.clearSynchronization();

        verify(activityRecordApi, never()).recordDocumentImported(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void appendFailureAfterCommitDoesNotFailTheCommittedUseCase() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new IllegalStateException("activity unavailable"))
                .when(activityRecordApi).recordDocumentImported(org.mockito.ArgumentMatchers.any());

        acl.recordDocumentImported(task());

        assertThatCode(TransactionSynchronizationUtils::triggerAfterCommit).doesNotThrowAnyException();
    }

    private IngestionTask task() {
        return IngestionTask.builder()
                .id("task-1").kbId("kb-1").status(IngestionTaskStatus.RUNNING)
                .totalCount(4).successCount(1).failureCount(1).runningCount(2).build();
    }
}
