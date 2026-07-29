package com.anchr.core.activity.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Recent imported document task page response.
 */
@Value
@Builder
public class RecentDocumentListDTO {

    List<RecentDocumentDTO> items;
    String nextCursor;
}
