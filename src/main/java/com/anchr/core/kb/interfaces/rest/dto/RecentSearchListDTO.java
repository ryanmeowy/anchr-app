package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Recent search page response.
 */
@Value
@Builder
public class RecentSearchListDTO {

    List<RecentSearchDTO> items;
    String nextCursor;
}
