package com.anchr.core.ingestion.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Text chunk generated from parsed text units.
 */
@Data
@NoArgsConstructor
public class TextChunk {

    private String segmentId;
    private String kbId;
    private String assetId;
    private String title;
    private Integer pageNo;
    private String chunkText;
    private Integer chunkOrder;
    private String sourceRef;
    private List<Float> embedding;

    public TextChunk(String segmentId, String assetId, String title, Integer pageNo,
                     String chunkText, Integer chunkOrder, String sourceRef, List<Float> embedding) {
        this(segmentId, null, assetId, title, pageNo, chunkText, chunkOrder, sourceRef, embedding);
    }

    public TextChunk(String segmentId, String kbId, String assetId, String title, Integer pageNo,
                     String chunkText, Integer chunkOrder, String sourceRef, List<Float> embedding) {
        this.segmentId = segmentId;
        this.kbId = kbId;
        this.assetId = assetId;
        this.title = title;
        this.pageNo = pageNo;
        this.chunkText = chunkText;
        this.chunkOrder = chunkOrder;
        this.sourceRef = sourceRef;
        this.embedding = embedding;
    }
}
