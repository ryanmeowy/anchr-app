package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Paged knowledge base list response.
 */
@Value
@Builder
public class KnowledgeBaseListDTO {

    List<KnowledgeBaseDTO> items;
    long total;
    int page;
    int size;
}
