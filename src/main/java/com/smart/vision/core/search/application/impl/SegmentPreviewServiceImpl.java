package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.application.SegmentPreviewService;
import com.smart.vision.core.search.application.support.PreviewAccessCache;
import com.smart.vision.core.search.domain.model.Bbox;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.port.SearchObjectStoragePort;
import com.smart.vision.core.search.domain.repository.KbSegmentRepository;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.smart.vision.core.search.interfaces.rest.dto.SurroundingChunkDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default segment preview service.
 */
@Service
@RequiredArgsConstructor
public class SegmentPreviewServiceImpl implements SegmentPreviewService {

    private static final int SURROUNDING_CHUNK_MAX_BYTES = 4096;
    private static final int SURROUNDING_CHUNK_WINDOW = 1;
    private static final long PREVIEW_URL_TTL_MILLIS = 5 * 60 * 1_000L;
    private static final String RELATION_CURRENT = "current";
    private static final String RELATION_PREVIOUS = "previous";
    private static final String RELATION_NEXT = "next";

    private final KbSegmentRepository kbSegmentRepository;
    private final SearchObjectStoragePort objectStoragePort;
    private final PreviewAccessCache previewAccessCache;

    @Override
    public PreviewSegmentDTO getSegmentPreview(String segmentId, String accessToken) {
        if (!StringUtils.hasText(segmentId)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "segmentId cannot be blank.");
        }
        if (!StringUtils.hasText(accessToken)) {
            throw new BusinessException(ApiError.UNAUTHORIZED, "X-Access-Token is required.");
        }
        Segment segment = kbSegmentRepository.findBySegmentId(segmentId.trim())
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND, "Segment not found."));
        return toPreview(segment, accessToken);
    }

    private PreviewSegmentDTO toPreview(Segment segment, String accessToken) {
        PreviewAccessCache.PreviewAccess previewAccess = buildPreviewAccess(segment, accessToken);
        return PreviewSegmentDTO.builder()
                .segmentId(segment.getSegmentId())
                .assetId(segment.getAssetId())
                .assetType(toCode(segment.getAssetType()))
                .segmentType(toCode(segment.getSegmentType()))
                .fileName(resolveFileName(segment))
                .previewType(resolvePreviewType(segment))
                .previewUrl(previewAccess.url())
                .expiresAt(previewAccess.expiresAt())
                .sourceRef(segment.getSourceRef())
                .thumbnail(segment.getThumbnail())
                .title(segment.getTitle())
                .snippet(resolveSnippet(segment))
                .ocrSummary(segment.getOcrSummary())
                .anchor(toAnchor(segment))
                .surroundingChunks(buildSurroundingChunks(segment))
                .build();
    }

    private PreviewAnchorDTO toAnchor(Segment segment) {
        if (segment == null) {
            return null;
        }
        if (segment.getPageNo() == null
                && segment.getChunkOrder() == null
                && segment.getBbox() == null
                && segment.getImageWidth() == null
                && segment.getImageHeight() == null) {
            return null;
        }
        return PreviewAnchorDTO.builder()
                .pageNo(segment.getPageNo())
                .chunkOrder(segment.getChunkOrder())
                .bbox(toBbox(segment.getBbox()))
                .imageWidth(segment.getImageWidth())
                .imageHeight(segment.getImageHeight())
                .build();
    }

    private PreviewAnchorDTO.BboxDTO toBbox(Bbox source) {
        if (source == null) {
            return null;
        }
        return PreviewAnchorDTO.BboxDTO.builder()
                .x(source.getX())
                .y(source.getY())
                .width(source.getWidth())
                .height(source.getHeight())
                .unit(source.getUnit())
                .build();
    }

    private List<SurroundingChunkDTO> buildSurroundingChunks(Segment segment) {
        if (!StringUtils.hasText(segment.getAssetId()) || segment.getChunkOrder() == null) {
            return buildCurrentChunk(segment);
        }
        List<SurroundingChunkDTO> chunks = kbSegmentRepository.findNeighborChunks(
                        segment.getAssetId(),
                        segment.getPageNo(),
                        segment.getChunkOrder(),
                        SURROUNDING_CHUNK_WINDOW)
                .stream()
                .map(candidate -> toSurroundingChunk(segment, candidate))
                .filter(Objects::nonNull)
                .toList();
        return chunks.isEmpty() ? buildCurrentChunk(segment) : chunks;
    }

    private List<SurroundingChunkDTO> buildCurrentChunk(Segment segment) {
        String content = resolveSnippet(segment);
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        return List.of(SurroundingChunkDTO.builder()
                .segmentId(segment.getSegmentId())
                .chunkOrder(segment.getChunkOrder())
                .pageNo(segment.getPageNo())
                .content(truncateUtf8(content.trim(), SURROUNDING_CHUNK_MAX_BYTES))
                .relation(RELATION_CURRENT)
                .build());
    }

    private SurroundingChunkDTO toSurroundingChunk(Segment current, Segment candidate) {
        String content = resolveSnippet(candidate);
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return SurroundingChunkDTO.builder()
                .segmentId(candidate.getSegmentId())
                .chunkOrder(candidate.getChunkOrder())
                .pageNo(candidate.getPageNo())
                .content(truncateUtf8(content.trim(), SURROUNDING_CHUNK_MAX_BYTES))
                .relation(resolveRelation(current, candidate))
                .build();
    }

    private String resolveRelation(Segment current, Segment candidate) {
        if (Objects.equals(current.getSegmentId(), candidate.getSegmentId())
                || Objects.equals(current.getChunkOrder(), candidate.getChunkOrder())) {
            return RELATION_CURRENT;
        }
        if (candidate.getChunkOrder() != null && current.getChunkOrder() != null
                && candidate.getChunkOrder() < current.getChunkOrder()) {
            return RELATION_PREVIOUS;
        }
        return RELATION_NEXT;
    }

    private PreviewAccessCache.PreviewAccess buildPreviewAccess(Segment segment, String accessToken) {
        String sourceRef = segment.getSourceRef();
        if (!StringUtils.hasText(sourceRef)) {
            return new PreviewAccessCache.PreviewAccess(null, null);
        }
        String normalizedSourceRef = sourceRef.trim();
        if (isDirectUrl(normalizedSourceRef)) {
            return new PreviewAccessCache.PreviewAccess(normalizedSourceRef, null);
        }
        String segmentId = segment.getSegmentId();
        if (StringUtils.hasText(segmentId)) {
            Optional<PreviewAccessCache.PreviewAccess> cached = previewAccessCache.find(segmentId, accessToken);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        String objectKey = resolveObjectKey(normalizedSourceRef);
        if (!StringUtils.hasText(objectKey)) {
            return new PreviewAccessCache.PreviewAccess(null, null);
        }
        String previewUrl = signPreviewUrl(objectKey);
        if (!StringUtils.hasText(previewUrl)) {
            throw new BusinessException(ApiError.PREVIEW_URL_SIGN_FAILED);
        }
        PreviewAccessCache.PreviewAccess previewAccess = new PreviewAccessCache.PreviewAccess(
                previewUrl,
                System.currentTimeMillis() + PREVIEW_URL_TTL_MILLIS
        );
        if (StringUtils.hasText(segmentId)) {
            previewAccessCache.save(segmentId, accessToken, previewAccess);
        }
        return previewAccess;
    }

    private String signPreviewUrl(String objectKey) {
        try {
            return objectStoragePort.buildPreviewUrl(objectKey);
        } catch (Exception e) {
            throw new BusinessException(ApiError.PREVIEW_URL_SIGN_FAILED, e);
        }
    }

    private boolean isDirectUrl(String sourceRef) {
        return sourceRef.startsWith("http://") || sourceRef.startsWith("https://");
    }

    private String resolveObjectKey(String sourceRef) {
        int queryIndex = sourceRef.indexOf('?');
        String path = queryIndex >= 0 ? sourceRef.substring(0, queryIndex) : sourceRef;
        String objectKey = path.startsWith("oss://") ? path.substring("oss://".length()) : path;
        while (objectKey.startsWith("/")) {
            objectKey = objectKey.substring(1);
        }
        return objectKey;
    }

    private String resolvePreviewType(Segment segment) {
        String extension = resolveExtension(resolveFileName(segment));
        if (StringUtils.hasText(extension)) {
            String type = previewTypeFromExtension(extension);
            if (type != null) {
                return type;
            }
        }
        if (segment.getAssetType() != null) {
            return switch (segment.getAssetType()) {
                case IMAGE -> "IMAGE";
                case TEXT -> "TXT";
            };
        }
        if (segment.getSegmentType() != null && segment.getSegmentType().name().startsWith("IMAGE_")) {
            return "IMAGE";
        }
        return "TXT";
    }

    private String resolveExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String normalized = fileName.trim();
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == normalized.length() - 1) {
            return null;
        }
        return normalized.substring(dotIndex + 1).toLowerCase();
    }

    private String previewTypeFromExtension(String extension) {
        return switch (extension) {
            case "pdf" -> "PDF";
            case "txt" -> "TXT";
            case "md", "markdown" -> "MD";
            case "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "IMAGE";
            default -> null;
        };
    }

    private String resolveSnippet(Segment segment) {
        if (StringUtils.hasText(segment.getOcrText())) {
            return segment.getOcrText();
        }
        if (StringUtils.hasText(segment.getContentText())) {
            return segment.getContentText();
        }
        return segment.getTitle();
    }

    private String resolveFileName(Segment segment) {
        if (StringUtils.hasText(segment.getSourceRef())) {
            String sourceRef = segment.getSourceRef().trim();
            int queryIndex = sourceRef.indexOf('?');
            String path = queryIndex >= 0 ? sourceRef.substring(0, queryIndex) : sourceRef;
            int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (slashIndex < 0 || slashIndex == path.length() - 1) {
                return path;
            }
            return path.substring(slashIndex + 1);
        }
        return StringUtils.hasText(segment.getTitle()) ? segment.getTitle().trim() : null;
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (!StringUtils.hasText(value) || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String current = new String(Character.toChars(codePoint));
            int currentBytes = current.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + currentBytes > maxBytes) {
                break;
            }
            builder.append(current);
            usedBytes += currentBytes;
            offset += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private String toCode(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
