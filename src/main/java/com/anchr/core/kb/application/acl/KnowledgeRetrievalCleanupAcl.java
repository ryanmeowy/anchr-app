package com.anchr.core.kb.application.acl;

import com.anchr.core.search.application.api.RetrievalCleanupApi;
import com.anchr.core.search.application.api.model.RetrievalAssetCleanupCommand;
import com.anchr.core.search.application.api.model.RetrievalGenerationCleanupCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Knowledge Content-owned translation of durable cleanup events. */
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalCleanupAcl {

    private final RetrievalCleanupApi retrievalCleanupApi;

    public void deleteAsset(String kbId, String assetId) {
        retrievalCleanupApi.deleteAsset(new RetrievalAssetCleanupCommand(kbId, assetId));
    }

    public void deleteGeneration(String kbId, String assetId, long generation) {
        retrievalCleanupApi.deleteGeneration(
                new RetrievalGenerationCleanupCommand(kbId, assetId, generation));
    }
}
