package com.anchr.core.search.infrastructure.persistence.es.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.anchr.core.search.interfaces.rest.dto.GraphTripleDTO;

import java.util.List;
import java.util.Optional;

/**
 * Matcher for graph triples (S/P/O) -> ES query fragment.
 * <p>
 * Keeps graph query construction consistent with other matcher abstractions (e.g. FieldMatcher).
 */
public interface GraphTriplesMatcher extends QueryFragmentMatcher<List<GraphTripleDTO>> {

    @Override
    Optional<Query> match(List<GraphTripleDTO> triples);
}
