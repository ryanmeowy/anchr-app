package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalPageQuery;
import com.anchr.core.search.application.api.model.RetrievalPageResult;

/** Public Retrieval capability for paged product search. */
public interface RetrievalPageQueryApi {

    RetrievalPageResult query(RetrievalPageQuery query);
}
