package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import co.elastic.clients.elasticsearch.core.SearchRequest;

/**
 * Query specification: describes how to build a SearchRequest.
 * <p>
 * Phase 1 (introduction): specs are added without switching repository traffic.
 */
public interface QuerySpec {

    SearchRequest toSearchRequest();
}

