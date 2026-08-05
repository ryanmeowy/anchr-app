package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.SegmentRebuildMutationTracker;
import com.anchr.core.search.application.acl.RetrievalCapabilityAcl;
import com.anchr.core.search.application.api.RetrievalEmbeddingDeploymentApi;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SegmentIndexManagerSpringWiringTest {

    @Test
    void springShouldConstructTheManagerAndItsCollaborators() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            registerMock(context, ElasticsearchClient.class);
            registerMock(context, EmbeddingProfileProvider.class);
            registerMock(context, SearchEmbeddingPort.class);
            registerMock(context, SearchObjectStoragePort.class);
            registerMock(context, IdGen.class);
            registerMock(context, RuntimeConfigUnit.class);
            context.registerBean(
                    "indexInitExecutor",
                    Executor.class,
                    () -> Runnable::run);
            context.registerBean(
                    SegmentIndexWriteBarrier.class,
                    SegmentIndexWriteBarrier::new);
            context.registerBean(
                    SegmentRebuildMutationTracker.class,
                    SegmentRebuildMutationTracker::new);
            registerMock(context, SegmentIndexAliasManager.class);
            registerMock(context, RetrievalCapabilityAcl.class);
            context.register(
                    SegmentIndexTopologyInspector.class,
                    SegmentPhysicalIndexFactory.class,
                    SegmentIndexMigrationRunner.class,
                    SegmentIndexStatusAssembler.class,
                    SegmentIndexManagerImpl.class);
            context.refresh();

            SegmentIndexManagerImpl manager =
                    context.getBean(SegmentIndexManagerImpl.class);
            assertThat(context.getBean(RetrievalEmbeddingDeploymentApi.class))
                    .isSameAs(manager);
        }
    }

    private <T> void registerMock(
            AnnotationConfigApplicationContext context,
            Class<T> type
    ) {
        context.registerBean(type, () -> mock(type));
    }
}
