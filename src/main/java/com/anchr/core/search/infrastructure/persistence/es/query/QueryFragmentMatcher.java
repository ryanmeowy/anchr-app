package com.anchr.core.search.infrastructure.persistence.es.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.Optional;

/**
 * Generic matcher that converts an input (T) into an Elasticsearch Query fragment.
 * <p>
 * This is the common abstraction behind both FieldMatcher (String) and GraphTriplesMatcher (List of triples).
 */
public interface QueryFragmentMatcher<T> {

    Optional<Query> match(T input);
}

