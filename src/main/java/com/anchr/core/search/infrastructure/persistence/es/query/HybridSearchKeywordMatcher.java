package com.anchr.core.search.infrastructure.persistence.es.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.anchr.core.common.constant.EmbeddingConstant.DEFAULT_FIELD_NAME_BOOST;
import static com.anchr.core.common.constant.EmbeddingConstant.DEFAULT_OCR_BOOST;
import static com.anchr.core.common.constant.EmbeddingConstant.DEFAULT_TAG_BOOST;

@Component
public class HybridSearchKeywordMatcher implements FieldMatcher {

    @Override
    public Optional<Query> match(String keyword) {
        return match(keyword, true);
    }

    public Optional<Query> match(String keyword, boolean enableOcr) {
        return Optional.of(Query.of(q -> q.bool(b -> {
            if (enableOcr) {
                b.should(sh -> sh.constantScore(x -> x.filter(s -> s.match(m -> m.field("ocrContent").query(keyword))).boost(DEFAULT_OCR_BOOST)));
            }
            b.should(sh -> sh.constantScore(x -> x.filter(s -> s.match(m -> m.field("tags").query(keyword))).boost(DEFAULT_TAG_BOOST)));
            b.should(sh -> sh.constantScore(x -> x.filter(s -> s.match(m -> m.field("fileName").query(keyword))).boost(DEFAULT_FIELD_NAME_BOOST)));
            return b;
        })));
    }
}
