package com.anchr.core.search.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * ES-level filters for kb search recall.
 */
@Value
@Builder
public class SearchFilter {

    List<String> kbIds;
    List<String> assetIds;
    List<String> assetTypes;
    List<String> hitTypes;
    Long createdFrom;
    Long createdTo;
}
