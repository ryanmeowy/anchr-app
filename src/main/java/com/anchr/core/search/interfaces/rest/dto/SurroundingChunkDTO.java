package com.anchr.core.search.interfaces.rest.dto;

import com.anchr.core.common.model.BboxInfo;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

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
    private List<BboxInfo> bbox;
}
