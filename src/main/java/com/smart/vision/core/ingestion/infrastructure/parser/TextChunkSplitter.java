package com.smart.vision.core.ingestion.infrastructure.parser;

import com.smart.vision.core.ingestion.domain.model.TextAssetMetadata;
import com.smart.vision.core.ingestion.domain.model.TextChunk;
import com.smart.vision.core.ingestion.domain.model.TextParseResult;
import com.smart.vision.core.ingestion.domain.model.TextParseUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits parsed text units into deterministic chunks.
 */
@Component
public class TextChunkSplitter {

    private final int chunkSize;
    private final int chunkOverlap;

    public TextChunkSplitter(
            @Value("${app.ingestion.text.chunk-size:800}") int chunkSize,
            @Value("${app.ingestion.text.chunk-overlap:120}") int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public List<TextChunk> split(TextAssetMetadata metadata, TextParseResult parseResult) {
        if (metadata == null || parseResult == null || parseResult.getUnits() == null || parseResult.getUnits().isEmpty()) {
            return List.of();
        }

        int safeChunkSize = Math.max(1, chunkSize);
        int safeOverlap = Math.max(0, Math.min(chunkOverlap, safeChunkSize - 1));
        int step = safeChunkSize - safeOverlap;
        String title = StringUtils.hasText(metadata.getTitle()) ? metadata.getTitle() : metadata.getFileName();
        String assetId = metadata.getAssetId();

        List<TextChunk> chunks = new ArrayList<>();
        int chunkOrder = 0;
        for (TextParseUnit unit : parseResult.getUnits()) {
            if (unit == null || !StringUtils.hasText(unit.getText())) {
                continue;
            }
            String normalized = TextParserSupport.normalizeLineEnding(unit.getText());
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            for (int start = 0; start < normalized.length(); start += step) {
                int end = Math.min(normalized.length(), start + safeChunkSize);
                String chunkText = normalized.substring(start, end).trim();
                if (!StringUtils.hasText(chunkText)) {
                    if (end >= normalized.length()) {
                        break;
                    }
                    continue;
                }
                Integer pageNo = unit.getPageNo();
                String segmentId = buildSegmentId(assetId, pageNo, chunkOrder);
                chunks.add(new TextChunk(segmentId, assetId, title, pageNo, chunkText, chunkOrder, metadata.getObjectKey(), null));
                chunkOrder++;
                if (end >= normalized.length()) {
                    break;
                }
            }
        }
        return chunks;
    }

    private String buildSegmentId(String assetId, Integer pageNo, int chunkOrder) {
        return assetId + ":" + (pageNo == null ? "NA" : pageNo) + ":" + chunkOrder;
    }
}
