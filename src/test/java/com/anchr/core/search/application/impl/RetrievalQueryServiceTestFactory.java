package com.anchr.core.search.application.impl;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.search.application.QueryEmbeddingService;
import com.anchr.core.search.application.acl.SearchKnowledgeAcl;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import io.micrometer.core.instrument.MeterRegistry;

import static org.mockito.Mockito.mock;

final class RetrievalQueryServiceTestFactory {

    private RetrievalQueryServiceTestFactory() {
    }

    static RetrievalQueryServiceImpl create(
            SegmentRepository segmentRepository,
            QueryEmbeddingService queryEmbeddingService,
            SearchKnowledgeAcl searchKnowledgeAcl,
            SearchRerankPort rerankPort,
            RuntimeConfigUnit runtimeConfigUnit,
            MeterRegistry meterRegistry
    ) {
        return new RetrievalQueryServiceImpl(
                segmentRepository,
                queryEmbeddingService,
                searchKnowledgeAcl,
                runtimeConfigUnit,
                new RetrievalRrfFusionPolicy(),
                new RetrievalRerankPolicy(rerankPort, meterRegistry),
                new RetrievalResultAssembler(mock(SearchObjectStoragePort.class)),
                new RetrievalTopNAssembler());
    }
}
