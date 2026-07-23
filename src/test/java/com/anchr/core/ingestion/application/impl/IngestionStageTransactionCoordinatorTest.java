package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionStageTransactionCoordinatorTest {

    @Mock
    private IngestionTaskRepository ingestionTaskRepository;
    @Mock
    private AssetRepository assetRepository;

    @Test
    void transitionAndUpdateAssetStatus_shouldRollbackWhenAssetWriteThrows() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        IngestionStageTransactionCoordinator coordinator = transactionalCoordinator(
                transactionManager);
        IngestionClaimTransition transition = transition();
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .build();

        when(ingestionTaskRepository.transitionClaim(transition)).thenReturn(true);
        when(assetRepository.updateStatuses(
                "kb-1", "asset-1", "SUCCESS", "RUNNING", "user-a",
                transition.getUpdatedAt()))
                .thenThrow(new IllegalStateException("asset write failed"));

        assertThatThrownBy(() -> coordinator.transitionAndUpdateAssetStatus(
                transition, asset, "SUCCESS", "RUNNING"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("asset write failed");

        InOrder writes = inOrder(ingestionTaskRepository, assetRepository);
        writes.verify(ingestionTaskRepository).transitionClaim(transition);
        writes.verify(assetRepository).updateStatuses(
                "kb-1", "asset-1", "SUCCESS", "RUNNING", "user-a",
                transition.getUpdatedAt());
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isEqualTo(1);
    }

    private IngestionStageTransactionCoordinator transactionalCoordinator(
            PlatformTransactionManager transactionManager) {
        IngestionStageTransactionCoordinator target =
                new IngestionStageTransactionCoordinator(
                        ingestionTaskRepository, assetRepository);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (IngestionStageTransactionCoordinator) proxyFactory.getProxy();
    }

    private IngestionClaimTransition transition() {
        LocalDateTime now = LocalDateTime.now();
        return IngestionClaimTransition.builder()
                .itemId("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .executionEpoch(1L)
                .expectedExecutionStage(IngestionExecutionStage.EMBED)
                .expectedClaimVersion(2)
                .leaseToken("lease-1")
                .nextExecutionStage(IngestionExecutionStage.INDEX)
                .nextStageRetryCount(0)
                .nextStageStartedAt(now)
                .nextActionAt(now)
                .stage(IngestionStage.INDEX)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(75)
                .parseAttempt(1)
                .doclingRequestId("task-1:item-1:1")
                .sourceRevision("v1:revision")
                .updatedBy("user-a")
                .updatedAt(now)
                .build();
    }

    private static final class RecordingTransactionManager
            implements PlatformTransactionManager {

        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
