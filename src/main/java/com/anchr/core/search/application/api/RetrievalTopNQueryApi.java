package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalTopNQuery;
import com.anchr.core.search.application.api.model.RetrievalTopNResult;

/** Public Retrieval capability for bounded Top-N search. */
public interface RetrievalTopNQueryApi {

    RetrievalTopNResult query(RetrievalTopNQuery query);
}
