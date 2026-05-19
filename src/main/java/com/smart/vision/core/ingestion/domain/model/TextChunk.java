package com.smart.vision.core.ingestion.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Text chunk generated from parsed text units.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextChunk {

    private String segmentId;
    private String assetId;
    private String title;
    private Integer pageNo;
    private String chunkText;
    private Integer chunkOrder;
    private String sourceRef;
    private List<Float> embedding;
}
