package com.anchr.core.search.infrastructure.persistence.es.query;

import cn.hutool.core.collection.CollectionUtil;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.anchr.core.search.interfaces.rest.dto.GraphTripleDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Builds graph (S,P,O triples) ES query fragment.
 * <p>
 * Extracted out from HybridQuerySpec to keep spec focused on orchestration.
 */
@Component
public class GraphTriplesSearchMatcher implements GraphTriplesMatcher {

    @Override
    public Optional<Query> match(List<GraphTripleDTO> triples) {
        if (CollectionUtil.isEmpty(triples)) {
            return Optional.empty();
        }
        return Optional.of(buildGraphClause(triples));
    }

    private static Query buildGraphClause(List<GraphTripleDTO> triples) {
        BoolQuery.Builder graphBool = new BoolQuery.Builder();
        for (GraphTripleDTO t : triples) {
            graphBool.should(g -> g.nested(n -> n
                    .path("relations")
                    .query(nq -> nq.bool(nb -> nb
                            .must(m -> m.term(trm -> trm.field("relations.s").value(t.getS())))
                            .must(m -> m.term(trm -> trm.field("relations.p").value(t.getP())))
                            .must(m -> m.term(trm -> trm.field("relations.o").value(t.getO())))
                    ))
            ));
        }

        return Query.of(sh -> sh.constantScore(c -> c.filter(graphBool.build()._toQuery()).boost(2.0f)));
    }
}
