package com.anchr.core.ingestion.domain.model;

import com.anchr.core.common.model.BboxInfo;
import lombok.Builder;
import lombok.Data;

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
    private String ocrText;
    private Integer chunkOrder;
    private String sourceRef;
    private List<Float> embedding;
    private List<BboxInfo> bboxInfos;
}
