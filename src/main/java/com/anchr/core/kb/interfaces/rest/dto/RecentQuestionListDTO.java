package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Recent question page response.
 */
@Value
@Builder
public class RecentQuestionListDTO {

    List<RecentQuestionDTO> items;
    String nextCursor;
}
