package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Paged document asset list response.
 */
@Value
@Builder
public class DocumentAssetListDTO {

    List<DocumentAssetDTO> items;
    long total;
    int page;
    int size;
}
