package com.anchr.core.kb.application.acl;

import com.anchr.core.search.application.api.RetrievalCleanupApi;
import com.anchr.core.search.application.api.model.RetrievalAssetCleanupCommand;
import com.anchr.core.search.application.api.model.RetrievalGenerationCleanupCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalCleanupAclTest {

    @Mock private RetrievalCleanupApi api;

    @Test
    void shouldTranslateDurableCleanupEventsToRetrievalCommands() {
        KnowledgeRetrievalCleanupAcl acl = new KnowledgeRetrievalCleanupAcl(api);

        acl.deleteAsset("kb-1", "asset-1");
        acl.deleteGeneration("kb-1", "asset-1", 2L);

        verify(api).deleteAsset(new RetrievalAssetCleanupCommand("kb-1", "asset-1"));
        verify(api).deleteGeneration(
                new RetrievalGenerationCleanupCommand("kb-1", "asset-1", 2L));
    }
}
