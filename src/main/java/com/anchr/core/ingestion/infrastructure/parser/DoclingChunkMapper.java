package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.common.model.ParseResponse;
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

    public List<Chunk> toTextChunks(Asset asset, ParseResponse response) {
        List<ParseResponse.Chunk> chunks = response.chunks();
        boolean isImg = isImage(asset.getFileType());

        if (CollectionUtils.isEmpty(chunks)) return Lists.newArrayList();
        return chunks.stream()
                .filter(Objects::nonNull)
                .map(chunk -> convert2Chunk(chunk, asset, isImg))
                .toList();
    }

    private Chunk convert2Chunk(ParseResponse.Chunk chunk, Asset asset, boolean isImg) {
        Chunk res = Chunk.builder()
                .segmentId(idgen.nextIdStr())
                .kbId(asset.getKbId())
                .assetId(asset.getId())
                .title(CollectionUtils.isEmpty(chunk.headings()) ? asset.getFileName() : chunk.headings().getFirst())
                .pageNo(CollectionUtils.isEmpty(chunk.pageRange()) ? 0 : chunk.pageRange().getFirst())
                .chunkOrder(parseChunkId(chunk.chunkId()))
                .sourceRef(asset.getObjectKey())
                .bboxInfos(Optional.ofNullable(chunk.bboxes()).orElse(Lists.newArrayList()).stream().map(BboxInfo::convert2BboxInfo).toList())
                .build();
        if (isImg) {
            res.setOcrText(chunk.textPlain());
        } else {
            res.setChunkText(chunk.textPlain());
        }
        return res;
    }

    private boolean isImage(String fileType) {
        return "image".equalsIgnoreCase(fileType);
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
