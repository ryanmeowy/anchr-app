package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalDocumentChunk;
import com.anchr.core.search.application.api.model.RetrievalDocumentContentQuery;

import java.util.List;

/** Provider API for reading one explicit document generation in source order. */
public interface RetrievalDocumentContentQueryApi {

    List<RetrievalDocumentChunk> query(RetrievalDocumentContentQuery query);
}
