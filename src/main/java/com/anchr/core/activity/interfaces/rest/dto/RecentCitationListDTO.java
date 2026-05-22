package com.anchr.core.activity.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Recent citation page response.
 */
@Value
@Builder
public class RecentCitationListDTO {

    List<RecentCitationDTO> items;
    String nextCursor;
}
