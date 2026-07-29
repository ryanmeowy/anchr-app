package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalGenerationIndexRequest;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;

/** Provider API for idempotently replacing one Asset generation in Retrieval. */
public interface RetrievalGenerationIndexApi {

    RetrievalGenerationWriteReceipt replaceGeneration(RetrievalGenerationIndexRequest request);
}
