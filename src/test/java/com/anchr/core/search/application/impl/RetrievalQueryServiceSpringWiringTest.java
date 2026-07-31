package com.anchr.core.search.application.impl;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.application.api.RetrievalHitQueryApi;
import com.anchr.core.search.application.api.RetrievalTopNQueryApi;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RetrievalQueryServiceSpringWiringTest {

    @Test
    void springShouldConstructTheServiceAndItsCollaborators() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            registerMock(context, SegmentRepository.class);
            registerMock(context, QueryEmbeddingService.class);
            registerMock(context, SearchKnowledgeAcl.class);
            registerMock(context, RuntimeConfigUnit.class);
            registerMock(context, SearchRerankPort.class);
            registerMock(context, SearchObjectStoragePort.class);
            registerMock(context, MeterRegistry.class);
            context.register(
                    RetrievalRrfFusionPolicy.class,
                    RetrievalRerankPolicy.class,
                    RetrievalResultAssembler.class,
                    RetrievalTopNAssembler.class,
                    RetrievalQueryServiceImpl.class);
            context.refresh();

            RetrievalQueryServiceImpl service =
                    context.getBean(RetrievalQueryServiceImpl.class);
            assertThat(context.getBean(RetrievalHitQueryApi.class))
                    .isSameAs(service);
            assertThat(context.getBean(RetrievalTopNQueryApi.class))
                    .isSameAs(service);
        }
    }

    private <T> void registerMock(
            AnnotationConfigApplicationContext context,
            Class<T> type
    ) {
        context.registerBean(type, () -> mock(type));
    }
}
