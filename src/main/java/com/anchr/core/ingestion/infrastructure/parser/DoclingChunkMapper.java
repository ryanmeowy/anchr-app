package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.search.domain.model.SegmentIdentity;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Maps docling chunks into the ingestion pipeline's TextChunk model.
 */
@Slf4j
@Component
public class DoclingChunkMapper {

    public List<Chunk> toTextChunks(
            Asset asset, ParseResponse response, long targetIndexGeneration) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(response, "response");
        if (!StringUtils.hasText(asset.getId())) {
            throw new IllegalArgumentException("asset.id cannot be blank.");
        }
        if (targetIndexGeneration < 1) {
            throw new IllegalArgumentException(
                    "targetIndexGeneration must be positive.");
        }
        List<ParseResponse.Chunk> chunks = response.chunks();
        boolean isImg = isImage(asset.getFileType());

        if (CollectionUtils.isEmpty(chunks)) {
            return Lists.newArrayList();
        }
        return IntStream.range(0, chunks.size())
                .filter(index -> chunks.get(index) != null)
                .mapToObj(index -> convert2Chunk(
                        chunks.get(index), index, asset, isImg, targetIndexGeneration))
                .toList();
    }

    private Chunk convert2Chunk(
            ParseResponse.Chunk chunk,
            int fallbackChunkOrder,
            Asset asset,
            boolean isImg,
            long targetIndexGeneration) {
        int pageNo = firstPageNo(chunk);
        int chunkOrder = parseChunkId(chunk.chunkId(), fallbackChunkOrder);
        Chunk res = Chunk.builder()
                .segmentId(SegmentIdentity.chunk(
                        asset.getId(),
                        targetIndexGeneration,
                        chunk.chunkId(),
                        pageNo,
                        chunkOrder,
                        StringUtils.hasText(chunk.textPlain())
                                ? chunk.textPlain() : chunk.text()))
                .kbId(asset.getKbId())
                .assetId(asset.getId())
                .title(CollectionUtils.isEmpty(chunk.headings()) ? asset.getFileName() : chunk.headings().getFirst())
                .pageNo(pageNo)
                .chunkOrder(chunkOrder)
                .sourceRef(stableSourceRef(asset))
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

    private String stableSourceRef(Asset asset) {
        return StringUtils.hasText(asset.getObjectKey())
                ? asset.getObjectKey().trim()
                : asset.getSourceUrl();
    }

    private int firstPageNo(ParseResponse.Chunk chunk) {
        if (CollectionUtils.isEmpty(chunk.pageRange())
                || chunk.pageRange().getFirst() == null) {
            return 0;
        }
        return chunk.pageRange().getFirst();
    }

    private int parseChunkId(String chunkId, int fallbackChunkOrder) {
        if (!StringUtils.hasText(chunkId)) return fallbackChunkOrder;
        try {
            String[] split = chunkId.split("/");
            return Integer.parseInt(split[split.length - 1]);
        } catch (Exception e) {
            log.warn("Failed to parse chunk id: {}", chunkId, e);
            return fallbackChunkOrder;
        }
    }

}
