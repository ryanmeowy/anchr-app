package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Text chunk generated from parsed text units.
 */
@Data
@Builder
public class Chunk {
    private String segmentId;
    private String kbId;
    private String assetId;
    private String title;
    private Integer pageNo;
    private String chunkText;
    private Integer chunkOrder;
    private String sourceRef;
    private List<Float> embedding;

}
