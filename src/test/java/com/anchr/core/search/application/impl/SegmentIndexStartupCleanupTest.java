package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.acl.RetrievalCapabilityAcl;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager.AliasTopology;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SegmentIndexStartupCleanupTest {

    private static final String INDEX = "kb_segment_20260101000000";

    @Test
    void startupCleanupIsSkippedWhenAliasTopologyIsUnavailable() {
        SegmentIndexAliasManager aliasManager = mock(SegmentIndexAliasManager.class);
        when(aliasManager.inspect()).thenReturn(
                AliasTopology.unavailable("elasticsearch is starting"));
        SegmentPhysicalIndexFactory indexFactory = mock(SegmentPhysicalIndexFactory.class);

        SegmentIndexManagerImpl manager = newManager(aliasManager, indexFactory);
        manager.onReady();

        verify(indexFactory, never()).cleanupAbandonedRebuildTargets(any());
    }

    @Test
    void startupCleanupIsSkippedWhenAliasTopologyIsInvalid() {
        SegmentIndexAliasManager aliasManager = mock(SegmentIndexAliasManager.class);
        when(aliasManager.inspect()).thenReturn(new AliasTopology(
                true, true, false, true, false, INDEX, null,
                "readTargets=[" + INDEX + "], writeTargets=[]"));
        SegmentPhysicalIndexFactory indexFactory = mock(SegmentPhysicalIndexFactory.class);

        SegmentIndexManagerImpl manager = newManager(aliasManager, indexFactory);
        manager.onReady();

        verify(indexFactory, never()).cleanupAbandonedRebuildTargets(any());
    }

    @Test
    void startupCleanupProtectsTheServingIndicesWhenTopologyIsValid() {
        SegmentIndexAliasManager aliasManager = mock(SegmentIndexAliasManager.class);
        when(aliasManager.inspect()).thenReturn(new AliasTopology(
                true, true, true, true, true, INDEX, INDEX, null));
        SegmentPhysicalIndexFactory indexFactory = mock(SegmentPhysicalIndexFactory.class);

        SegmentIndexManagerImpl manager = newManager(aliasManager, indexFactory);
        manager.onReady();

        verify(indexFactory).cleanupAbandonedRebuildTargets(Set.of(INDEX));
    }

    @Test
    void cleanupRefusesToRunWithoutAProtectedServingIndex() {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        SegmentPhysicalIndexFactory indexFactory = new SegmentPhysicalIndexFactory(esClient);

        indexFactory.cleanupAbandonedRebuildTargets(Set.of());
        indexFactory.cleanupAbandonedRebuildTargets(null);

        verifyNoInteractions(esClient);
    }

    private static SegmentIndexManagerImpl newManager(
            SegmentIndexAliasManager aliasManager,
            SegmentPhysicalIndexFactory indexFactory
    ) {
        return SegmentIndexManagerTestFactory.create(
                null,
                Optional::empty,
                null,
                null,
                null,
                Runnable::run,
                new SegmentIndexWriteBarrier(),
                aliasManager,
                indexFactory,
                mock(RetrievalCapabilityAcl.class));
    }
}
