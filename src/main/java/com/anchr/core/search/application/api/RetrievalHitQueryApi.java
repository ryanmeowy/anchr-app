package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalHitQuery;

import java.util.List;

/** Public Retrieval capability for callers that only need ranked hits. */
public interface RetrievalHitQueryApi {

    List<RetrievalHit> query(RetrievalHitQuery query);
}
