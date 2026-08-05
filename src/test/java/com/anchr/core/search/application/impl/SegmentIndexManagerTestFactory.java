package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.SegmentRebuildMutationTracker;
import com.anchr.core.search.application.acl.RetrievalCapabilityAcl;
import com.anchr.core.search.domain.port.EmbeddingProfileProvider;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;

import java.util.concurrent.Executor;

import static org.mockito.Mockito.mock;

final class SegmentIndexManagerTestFactory {

    private SegmentIndexManagerTestFactory() {
    }

    static SegmentIndexManagerImpl create(
            ElasticsearchClient esClient,
            EmbeddingProfileProvider embeddingProfileProvider,
            SearchEmbeddingPort embeddingPort,
            SearchObjectStoragePort storagePort,
            IdGen idGen,
            Executor indexInitExecutor,
            SegmentIndexWriteBarrier indexWriteBarrier,
            SegmentIndexAliasManager aliasManager
    ) {
        return create(
                esClient,
                embeddingProfileProvider,
                embeddingPort,
                storagePort,
                idGen,
                indexInitExecutor,
                indexWriteBarrier,
                aliasManager,
                mock(RetrievalCapabilityAcl.class));
    }

    static SegmentIndexManagerImpl create(
            ElasticsearchClient esClient,
            EmbeddingProfileProvider embeddingProfileProvider,
            SearchEmbeddingPort embeddingPort,
            SearchObjectStoragePort storagePort,
            IdGen idGen,
            Executor indexInitExecutor,
            SegmentIndexWriteBarrier indexWriteBarrier,
            SegmentIndexAliasManager aliasManager,
            RetrievalCapabilityAcl retrievalCapabilityAcl
    ) {
        return new SegmentIndexManagerImpl(
                esClient,
                embeddingProfileProvider,
                embeddingPort,
                indexInitExecutor,
                indexWriteBarrier,
                new SegmentRebuildMutationTracker(),
                aliasManager,
                new SegmentIndexTopologyInspector(esClient, aliasManager),
                new SegmentPhysicalIndexFactory(esClient),
                new SegmentIndexMigrationRunner(esClient, storagePort, idGen, null),
                new SegmentIndexStatusAssembler(),
                retrievalCapabilityAcl);
    }
}
