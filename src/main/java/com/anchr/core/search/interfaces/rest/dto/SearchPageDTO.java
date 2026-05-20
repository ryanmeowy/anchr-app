package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Paged search response.
 */
@Data
@Builder
public class SearchPageDTO implements Serializable {

    private List<SearchResultDTO> items;

    private String nextCursor;

    private boolean hasMore;
}
