package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalAssetCleanupCommand;
import com.anchr.core.search.application.api.model.RetrievalGenerationCleanupCommand;

/** Provider API for idempotent Retrieval projection cleanup. */
public interface RetrievalCleanupApi {

    void deleteAsset(RetrievalAssetCleanupCommand command);

    void deleteGeneration(RetrievalGenerationCleanupCommand command);
}
