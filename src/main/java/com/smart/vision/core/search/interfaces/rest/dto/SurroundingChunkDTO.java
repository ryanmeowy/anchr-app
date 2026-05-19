package com.smart.vision.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Neighbor chunk used for text preview fallback positioning.
 */
@Data
@Builder
public class SurroundingChunkDTO implements Serializable {

    private String segmentId;
    private Integer chunkOrder;
    private Integer pageNo;
    private String content;
    private String relation;
}
