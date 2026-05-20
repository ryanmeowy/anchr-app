package com.anchr.core.search.domain.model;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SortContext {
    private SortOptions.Kind kind;
    private String field;
    private SortOrder order;
}