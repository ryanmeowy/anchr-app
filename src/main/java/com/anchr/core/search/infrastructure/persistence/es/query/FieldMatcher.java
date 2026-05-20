package com.anchr.core.search.infrastructure.persistence.es.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.Optional;

public interface FieldMatcher extends QueryFragmentMatcher<String> {

    @Override
    Optional<Query> match(String param);
}