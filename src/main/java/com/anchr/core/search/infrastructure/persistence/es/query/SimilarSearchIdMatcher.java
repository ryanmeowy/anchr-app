package com.anchr.core.search.infrastructure.persistence.es.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SimilarSearchIdMatcher implements FieldMatcher {

    @Override
    public Optional<Query> match(String id) {
        return Optional.of(Query.of(f -> f.bool(b -> b
                .mustNot(mn -> mn.term(t -> t.field("id").value(Long.parseLong(id)))))));
    }
}
