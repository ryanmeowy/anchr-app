package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.kb.domain.model.Asset;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
                .segmentId(deterministicSegmentId(
                        asset.getId(), targetIndexGeneration, chunk, pageNo, chunkOrder))
                .kbId(asset.getKbId())
                .assetId(asset.getId())
                .title(CollectionUtils.isEmpty(chunk.headings()) ? asset.getFileName() : chunk.headings().getFirst())
                .pageNo(pageNo)
                .chunkOrder(chunkOrder)
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

    private String deterministicSegmentId(
            String assetId,
            long targetIndexGeneration,
            ParseResponse.Chunk chunk,
            int pageNo,
            int chunkOrder) {
        String chunkIdentity;
        if (StringUtils.hasText(chunk.chunkId())) {
            chunkIdentity = "chunk-id\n" + chunk.chunkId().trim();
        } else {
            String text = StringUtils.hasText(chunk.textPlain())
                    ? chunk.textPlain() : chunk.text();
            chunkIdentity = "fallback\n"
                    + pageNo + "\n"
                    + chunkOrder + "\n"
                    + normalizeText(text);
        }
        String canonical = assetId.trim()
                + "\n" + targetIndexGeneration
                + "\n" + chunkIdentity;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private String normalizeText(String text) {
        return StringUtils.hasText(text)
                ? text.trim().replaceAll("\\s+", " ")
                : "";
    }
}
