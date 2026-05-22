package com.anchr.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Surrounding chunks response for preview page.
 */
@Data
@Builder
public class PreviewNeighborsDTO implements Serializable {

    private String segmentId;
    private List<SurroundingChunkDTO> items;
}
