package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.search.domain.model.SegmentType;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Maps docling chunks into the ingestion pipeline's TextChunk model.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoclingChunkMapper {

    private final IdGen idGen;

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

    /** Maps uploaded embedded-image artifacts to independent retrieval units. */
    public List<Chunk> toDocumentImageChunks(
            Asset asset, ParseResponse response, long targetIndexGeneration) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(response, "response");
        if (CollectionUtils.isEmpty(response.images())) {
            return List.of();
        }
        LinkedHashMap<String, ParseResponse.Image> unique = new LinkedHashMap<>();
        for (ParseResponse.Image image : response.images()) {
            if (image == null || !"UPLOADED".equalsIgnoreCase(image.uploadStatus())) {
                continue;
            }
            if (!Objects.equals(1, image.artifactVersion())
                    || !StringUtils.hasText(image.blockId())
                    || !StringUtils.hasText(image.imageObjectKey())) {
                log.warn("Ignoring invalid embedded image artifact for asset {}", asset.getId());
                continue;
            }
            unique.putIfAbsent(image.blockId().trim(), image);
        }
        List<Chunk> chunks = new ArrayList<>(unique.size());
        int order = 0;
        for (ParseResponse.Image image : unique.values()) {
            int chunkOrder = order++;
            List<BboxInfo> bboxes = Optional.ofNullable(image.bboxes())
                    .orElse(List.of()).stream()
                    .filter(Objects::nonNull)
                    .map(BboxInfo::convert2BboxInfo)
                    .toList();
            chunks.add(Chunk.builder()
                    .segmentId(idGen.nextIdStr())
                    .kbId(asset.getKbId())
                    .assetId(asset.getId())
                    .title(StringUtils.hasText(asset.getTitle())
                            ? asset.getTitle() : asset.getFileName())
                    .pageNo(image.pageNo())
                    .chunkOrder(chunkOrder)
                    .chunkText(joinText(image.caption(), image.alt(), image.contextText()))
                    .ocrText(trimToNull(image.ocrText()))
                    .sourceRef(image.imageObjectKey().trim())
                    .bboxInfos(bboxes)
                    .segmentType(SegmentType.DOCUMENT_IMAGE)
                    .imageWidth(image.imageWidth())
                    .imageHeight(image.imageHeight())
                    .build());
        }
        return List.copyOf(chunks);
    }

    private Chunk convert2Chunk(
            ParseResponse.Chunk chunk,
            int fallbackChunkOrder,
            Asset asset,
            boolean isImg,
            long targetIndexGeneration) {
        int pageNo = firstPageNo(chunk);
        int chunkOrder = parseChunkId(chunk.chunkId(), fallbackChunkOrder);
        SegmentType segmentType = isImg
                ? SegmentType.IMAGE_OCR_BLOCK
                : SegmentType.TEXT_CHUNK;
        Chunk res = Chunk.builder()
                .segmentId(idGen.nextIdStr())
                .kbId(asset.getKbId())
                .assetId(asset.getId())
                .title(CollectionUtils.isEmpty(chunk.headings()) ? asset.getFileName() : chunk.headings().getFirst())
                .pageNo(pageNo)
                .chunkOrder(chunkOrder)
                .segmentType(segmentType)
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
        if (!StringUtils.hasText(asset.getObjectKey())) {
            throw new IllegalArgumentException("asset.objectKey cannot be blank.");
        }
        return asset.getObjectKey().trim();
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

    private String joinText(String... values) {
        return java.util.Arrays.stream(values)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

}
