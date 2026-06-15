package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.integration.ai.ParseResponse;
import com.anchr.core.kb.domain.model.Asset;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps docling chunks into the ingestion pipeline's TextChunk model.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoclingChunkMapper {

    private final IdGen idgen;

    public List<Chunk> toTextChunks(Asset asset, List<ParseResponse.Chunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) return Lists.newArrayList();
        return chunks.stream()
                .filter(Objects::nonNull)
                .map(chunk -> convert2Chunk(chunk, asset))
                .toList();
    }

    private Chunk convert2Chunk(ParseResponse.Chunk chunk, Asset asset) {
        return Chunk.builder()
                .segmentId(idgen.nextIdStr())
                .kbId(asset.getKbId())
                .assetId(asset.getId())
                .title(StringUtils.hasText(asset.getTitle()) ? asset.getTitle() : asset.getFileName())
                .pageNo(CollectionUtils.isEmpty(chunk.pageRange()) ? 0 : chunk.pageRange().getFirst())
                .chunkText(chunk.textPlain())
                .chunkOrder(parseChunkId(chunk.chunkId()))
                .sourceRef(asset.getObjectKey())
                .bboxInfos(Optional.ofNullable(chunk.bboxes()).orElse(Lists.newArrayList()).stream().map(BboxInfo::convert2BboxInfo).toList())
                .embedding(null)
                .build();
    }

    private Integer parseChunkId(String chunkId) {
        if (!StringUtils.hasText(chunkId)) return 0;
        try {
            String[] split = chunkId.split("/");
            return Integer.parseInt(split[split.length - 1]);
        }catch (Exception e) {
            log.warn("Failed to parse chunk id: {}", chunkId, e);
            return 0;
        }
    }
}
