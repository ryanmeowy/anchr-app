package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionActivityAcl;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IngestionSpringWiringTest {

    @Test
    void facadeReceivesUseCasesAndMaintenanceOwnsTheTransactionBoundary() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfig.class);
            context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
            context.registerBean(KnowledgeBaseService.class, () -> mock(KnowledgeBaseService.class));
            context.registerBean(AssetRepository.class, () -> mock(AssetRepository.class));
            context.registerBean(KnowledgeBaseRepository.class,
                    () -> mock(KnowledgeBaseRepository.class));
            context.registerBean(IngestionTaskRepository.class,
                    () -> mock(IngestionTaskRepository.class));
            context.registerBean(IngestionCapabilityService.class,
                    () -> mock(IngestionCapabilityService.class));
            context.registerBean(IdGen.class, () -> mock(IdGen.class));
            context.registerBean(IngestionActivityAcl.class, () -> mock(IngestionActivityAcl.class));
            context.registerBean(IngestionTaskProcessor.class,
                    () -> mock(IngestionTaskProcessor.class));
            context.registerBean(IngestionCreateTransactionRunner.class,
                    () -> mock(IngestionCreateTransactionRunner.class));
            context.register(IngestionTaskQuery.class, IngestionTaskCreateUseCase.class,
                    IngestionTaskMaintenanceUseCase.class, IngestionApplicationServiceImpl.class);
            context.refresh();

            IngestionApplicationServiceImpl facade = context.getBean(IngestionApplicationServiceImpl.class);
            IngestionTaskMaintenanceUseCase maintenance =
                    context.getBean(IngestionTaskMaintenanceUseCase.class);
            assertThat(AopUtils.isAopProxy(maintenance)).isTrue();
            assertThat(AopUtils.isAopProxy(facade)).isFalse();
            assertThat(ReflectionTestUtils.getField(facade, "maintenanceUseCase"))
                    .isSameAs(maintenance);
            assertThat(context.getBeansOfType(IngestionTaskFactory.class)).isEmpty();

            assertThatThrownBy(() -> maintenance.retryFailed("kb-1", "task-1"))
                    .isInstanceOf(RuntimeException.class);
            verify(transactionManager).getTransaction(any(TransactionDefinition.class));
            verify(transactionManager).rollback(any(TransactionStatus.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {
    }
}
