package com.anchr.core.search.application.acl;

import com.anchr.core.activity.application.api.ActivityQueryApi;
import com.anchr.core.activity.application.api.ActivityRecordApi;
import com.anchr.core.activity.application.api.model.ActivityAnchor;
import com.anchr.core.activity.application.api.model.ActivityCitationChunk;
import com.anchr.core.activity.application.api.model.ActivityQueryResult;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.search.interfaces.rest.dto.CitationChunkSnapshotDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewRequestDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Search-side adapter for Activity write and citation-read capabilities. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchActivityAcl {

    private final ActivityRecordApi activityRecordApi;
    private final ActivityQueryApi activityQueryApi;

    public void recordSearchExecuted(SearchQueryDTO query, int total) {
        try {
            SearchQueryDTO.DateRange range = query.getDateRange();
            activityRecordApi.recordSearchExecuted(new ActivityRecordCommand.SearchExecuted(
                    UserContextHolder.get().userId(), query.getQuery(), query.getKbIds(), total,
                    query.getAssetTypes(), range == null ? null
                            : new ActivityRecordCommand.DateRange(range.getFrom(), range.getTo()),
                    query.getWithAnswer(), query.getAnswerMode(), LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("search activity record failed", e);
        }
    }

    public void recordCitationOpened(PreviewSegmentDTO preview, PreviewRequestDTO request) {
        try {
            PreviewSegmentDTO.CitationContextDTO context = preview.getCitationContext();
            PreviewRequestDTO.CitationInfo citationInfo = request.getCitationInfo();
            activityRecordApi.recordCitationOpened(new ActivityRecordCommand.CitationOpened(
                    UserContextHolder.get().userId(), preview.getSegmentId(), preview.getAssetId(), preview.getKbId(),
                    preview.getFileName(), preview.getTitle(), preview.getContent(), context.getCitationReason(),
                    citationInfo.getCitationIndex(), request.getQuestion(), request.getSourceType(),
                    request.getSourceId(), request.getSessionId(), toActivityAnchor(preview.getAnchor()),
                    toActivityChunks(citationInfo.getChunks()), LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("citation activity record failed, segmentId={}", preview == null ? null : preview.getSegmentId(), e);
        }
    }

    public CitationSnapshot findCitationById(String id) {
        return activityQueryApi.findCitationById(id).map(this::toSnapshot).orElseGet(CitationSnapshot::empty);
    }

    private CitationSnapshot toSnapshot(ActivityQueryResult.Citation citation) {
        return new CitationSnapshot(citation.sourceType(), citation.sourceId(), citation.sessionId(),
                citation.citationIndex(), citation.citationReason(), citation.question(),
                toPreviewAnchor(citation.anchor()));
    }

    private ActivityAnchor toActivityAnchor(PreviewAnchorDTO anchor) {
        if (anchor == null) {
            return null;
        }
        List<ActivityAnchor.ActivityBbox> boxes = anchor.getBbox() == null ? List.of()
                : anchor.getBbox().stream().map(this::toActivityBbox).toList();
        return new ActivityAnchor(anchor.getPageNo(), anchor.getChunkOrder(), boxes,
                anchor.getImageWidth(), anchor.getImageHeight());
    }

    private ActivityAnchor.ActivityBbox toActivityBbox(BboxInfo value) {
        BboxInfo.Bbox box = value.getBbox();
        ActivityAnchor.Bbox activityBox = box == null ? null : new ActivityAnchor.Bbox(
                box.getL(), box.getT(), box.getR(), box.getB(), box.getCoordOrigin());
        return new ActivityAnchor.ActivityBbox(activityBox, value.getPageNo());
    }

    private List<ActivityCitationChunk> toActivityChunks(List<CitationChunkSnapshotDTO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream().map(chunk -> new ActivityCitationChunk(
                chunk.getSegmentId(), chunk.getSegmentIndex(), chunk.getCitationLabel(), chunk.getTitle(),
                chunk.getPageNo(), chunk.getChunkOrder(), chunk.getContent(), chunk.getSnippet(),
                chunk.getHitType(), toActivityAnchor(chunk.getAnchor()), toActivityWhy(chunk.getWhy()))).toList();
    }

    private ActivityCitationChunk.Why toActivityWhy(CitationChunkSnapshotDTO.WhyDTO why) {
        if (why == null) {
            return null;
        }
        CitationChunkSnapshotDTO.MatchedByDTO matched = why.getMatchedBy();
        ActivityCitationChunk.MatchedBy activityMatched = matched == null ? null
                : new ActivityCitationChunk.MatchedBy(
                        matched.getVector(), matched.getTitle(), matched.getContent(), matched.getOcr());
        return new ActivityCitationChunk.Why(
                why.getScore(), why.getHitSources(), activityMatched, why.getMatchSummary(), why.getReason());
    }

    private PreviewAnchorDTO toPreviewAnchor(ActivityAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        return PreviewAnchorDTO.builder()
                .pageNo(anchor.pageNo()).chunkOrder(anchor.chunkOrder())
                .bbox(anchor.bbox().stream().map(this::toPreviewBbox).toList())
                .imageWidth(anchor.imageWidth()).imageHeight(anchor.imageHeight()).build();
    }

    private BboxInfo toPreviewBbox(ActivityAnchor.ActivityBbox value) {
        ActivityAnchor.Bbox box = value.bbox();
        BboxInfo.Bbox previewBox = box == null ? null : BboxInfo.Bbox.builder()
                .l(box.l()).t(box.t()).r(box.r()).b(box.b()).coordOrigin(box.coordOrigin()).build();
        return BboxInfo.builder().bbox(previewBox).pageNo(value.pageNo()).build();
    }

    public record CitationSnapshot(String sourceType, String sourceId, String sessionId,
                                   String citationIndex, String citationReason, String question,
                                   PreviewAnchorDTO anchor) {
        private static CitationSnapshot empty() {
            return new CitationSnapshot(null, null, null, null, null, null, null);
        }
    }
}
