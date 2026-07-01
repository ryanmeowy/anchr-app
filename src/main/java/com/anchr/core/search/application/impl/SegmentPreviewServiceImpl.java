package com.anchr.core.search.application.impl;

import cn.hutool.json.JSONUtil;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.kb.application.ActivityQueryService;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.interfaces.rest.dto.RecentCitationDTO;
import com.anchr.core.search.application.SearchCitationReasonService;
import com.anchr.core.search.application.SegmentPreviewService;
import com.anchr.core.search.application.support.PreviewAccessCache;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewNeighborsDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewRequestDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.anchr.core.search.interfaces.rest.dto.SurroundingChunkDTO;
import lombok.Builder;
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

    private final SegmentRepository kbSegmentRepository;
    private final SearchObjectStoragePort objectStoragePort;
    private final PreviewAccessCache previewAccessCache;
    private final ActivityEventService activityEventService;
    private final ActivityQueryService  activityQueryService;
    private final SearchCitationReasonService citationReasonService;
    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public PreviewSegmentDTO getSegmentPreview(String segmentId, PreviewRequestDTO request) {
        if (!StringUtils.hasText(segmentId)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "segmentId cannot be blank.");
        }
        String accessTokenHash = currentAccessTokenHash();
        Segment segment = kbSegmentRepository.findBySegmentId(segmentId.trim())
                .orElseThrow(() -> new BusinessException(ApiError.SEGMENT_NOT_FOUND));
        PreviewSegmentDTO preview = toPreview(segment, accessTokenHash, request);
        if (!StringUtils.hasText(request.getRecordId())) {
            recordCitationOpened(preview, request);
        }
        return preview;
    }

    @Override
    public PreviewSegmentDTO refreshSegmentPreview(String segmentId, PreviewRequestDTO request) {
        String accessTokenHash = currentAccessTokenHash();
        if (StringUtils.hasText(segmentId)) {
            previewAccessCache.evict(segmentId.trim(), accessTokenHash);
        }
        return getSegmentPreview(segmentId, request);
    }

    @Override
    public PreviewNeighborsDTO getSegmentNeighbors(String segmentId, int before, int after) {
        if (!StringUtils.hasText(segmentId)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "segmentId cannot be blank.");
        }
        Segment segment = kbSegmentRepository.findBySegmentId(segmentId.trim())
                .orElseThrow(() -> new BusinessException(ApiError.SEGMENT_NOT_FOUND));
        int window = Math.clamp(Math.max(before, after), 1, 10);
        return PreviewNeighborsDTO.builder()
                .segmentId(segment.getSegmentId())
                .items(buildSurroundingChunks(segment, window))
                .build();
    }

    private PreviewSegmentDTO toPreview(Segment segment, String accessTokenHash, PreviewRequestDTO request) {
        PreviewAccessCache.PreviewAccess previewAccess = buildPreviewAccess(segment, accessTokenHash);
        PreviewInfo previewInfo = fetchCitationInfo(request);

        return PreviewSegmentDTO.builder()
                .segmentId(segment.getSegmentId())
                .assetId(segment.getAssetId())
                .kbId(segment.getKbId())
                .kbName(Optional.of(knowledgeBaseService.get(segment.getKbId())).map(KnowledgeBase::getName).orElse(null))
                .assetType(segment.getAssetType())
                .segmentType(toCode(segment.getSegmentType()))
                .fileName(resolveFileName(segment))
                .previewType(segment.getAssetType())
                .previewUrl(previewAccess.url())
                .expiresAt(previewAccess.expiresAt())
                .sourceRef(segment.getSourceRef())
                .thumbnail(segment.getThumbnail())
                .title(segment.getTitle())
                .snippet(resolveSnippet(segment))
                .ocrSummary(segment.getOcrSummary())
                .anchor(toAnchor(segment))
                .surroundingChunks(buildSurroundingChunks(segment, SURROUNDING_CHUNK_WINDOW))
                .sourceType(previewInfo.sourceType)
                .sourceId(previewInfo.sourceId)
                .sessionId(previewInfo.sessionId)
                .sourceQuestion(previewInfo.question)
                .citationContext(buildCitationContext(segment, previewInfo, request))
                .build();
    }

    private PreviewInfo fetchCitationInfo(PreviewRequestDTO request) {
        if (StringUtils.hasText(request.getRecordId())) {
            RecentCitationDTO recentCitationDTO = activityQueryService.fetchCitationsById(request.getRecordId());
            return PreviewInfo.builder()
                    .sourceType(recentCitationDTO.getSourceType())
                    .sourceId(recentCitationDTO.getSourceId())
                    .sessionId(recentCitationDTO.getSessionId())
                    .citationIndex(recentCitationDTO.getCitationIndex())
                    .why(recentCitationDTO.getWhy())
                    .question(recentCitationDTO.getQuestion())
                    .build();
        }

        PreviewRequestDTO.CitationInfo citationInfo = request.getCitationInfo() == null
                ? new PreviewRequestDTO.CitationInfo() : request.getCitationInfo();
        return PreviewInfo.builder()
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceId())
                .sessionId(request.getSessionId())
                .citationIndex(citationInfo.getCitationIndex())
                .why(JSONUtil.toJsonStr(citationInfo.getWhy()))
                .question(request.getQuestion())
                .build();
    }


    @Builder
    record PreviewInfo(String sourceType, String sourceId, String sessionId, String citationIndex, String why, String question){}

    private void recordCitationOpened(PreviewSegmentDTO preview, PreviewRequestDTO request) {
        PreviewSegmentDTO.CitationContextDTO citationContext = preview.getCitationContext();
        PreviewRequestDTO.CitationInfo citationInfo = request.getCitationInfo();
        ActivityEventService.CitationContext cxt = ActivityEventService.CitationContext.builder()
                .segmentId(preview.getSegmentId())
                .assetId(preview.getAssetId())
                .kbId(preview.getKbId())
                .fileName(preview.getFileName())
                .title(preview.getTitle())
                .snippet(preview.getSnippet())
                .citationIndex(citationInfo.getCitationIndex())
                .citationReason(citationContext.getCitationReason())
                .sourceId(request.getSourceId())
                .sessionId(request.getSessionId())
                .sourceType(request.getSourceType())
                .question(request.getQuestion())
                .build();
        activityEventService.recordCitationOpened(cxt);
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
                .bbox(segment.getBbox())
                .imageWidth(segment.getImageWidth())
                .imageHeight(segment.getImageHeight())
                .build();
    }

    private List<SurroundingChunkDTO> buildSurroundingChunks(Segment segment, int window) {
        if (!StringUtils.hasText(segment.getAssetId()) || segment.getChunkOrder() == null) {
            return buildCurrentChunk(segment);
        }
        List<SurroundingChunkDTO> chunks = kbSegmentRepository.findNeighborChunks(
                        segment.getAssetId(),
                        segment.getChunkOrder(),
                        window)
                .stream()
                .map(candidate -> toSurroundingChunk(segment, candidate))
                .filter(Objects::nonNull)
                .toList();
        return chunks.isEmpty() ? buildCurrentChunk(segment) : chunks;
    }

    private PreviewSegmentDTO.CitationContextDTO buildCitationContext(Segment segment, PreviewInfo previewInfo, PreviewRequestDTO request) {
        if (segment == null || !StringUtils.hasText(resolveSnippet(segment))) {
            return null;
        }
        String reason = Optional.ofNullable(request.getCitationInfo()).map(PreviewRequestDTO.CitationInfo::getReason).orElse(null);
        return PreviewSegmentDTO.CitationContextDTO.builder()
                .citationIndex(previewInfo.citationIndex)
                .citationReason(null == previewInfo.why ? reason : buildCitationReason(previewInfo.why))
                .build();
    }

    private String buildCitationReason(String why) {
        if (!StringUtils.hasText(why)) {
            return null;
        }
        return citationReasonService.generate(why);
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

    private PreviewAccessCache.PreviewAccess buildPreviewAccess(Segment segment, String accessTokenHash) {
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
            Optional<PreviewAccessCache.PreviewAccess> cached = previewAccessCache.find(segmentId, accessTokenHash);
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
            previewAccessCache.save(segmentId, accessTokenHash, previewAccess);
        }
        return previewAccess;
    }

    private String currentAccessTokenHash() {
        String accessTokenHash = UserContextHolder.get().accessTokenHash();
        if (!StringUtils.hasText(accessTokenHash)) {
            throw new BusinessException(ApiError.UNAUTHORIZED, "Authenticated token context is required.");
        }
        return accessTokenHash;
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
