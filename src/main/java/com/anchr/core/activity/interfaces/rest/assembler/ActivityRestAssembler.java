package com.anchr.core.activity.interfaces.rest.assembler;

import com.anchr.core.activity.application.api.model.ActivityAnchor;
import com.anchr.core.activity.application.api.model.ActivityCitationChunk;
import com.anchr.core.activity.application.api.model.ActivityQueryResult;
import com.anchr.core.activity.interfaces.rest.dto.RecentCitationDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentDocumentDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentDocumentListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentSearchDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentSearchListDTO;
import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.search.interfaces.rest.dto.CitationChunkSnapshotDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import org.springframework.stereotype.Component;

/** Keeps the existing Activity REST JSON contract outside the Application API. */
@Component
public class ActivityRestAssembler {

    public RecentQuestionListDTO toQuestions(ActivityQueryResult.Page<ActivityQueryResult.Question> page) {
        return RecentQuestionListDTO.builder()
                .items(page.items().stream().map(item -> RecentQuestionDTO.builder()
                        .turnId(item.turnId()).sessionId(item.sessionId()).question(item.question())
                        .kbScope(item.kbScope()).knowledgeBaseNames(item.knowledgeBaseNames())
                        .createdAt(item.createdAt()).build()).toList())
                .nextCursor(page.nextCursor()).build();
    }

    public RecentCitationListDTO toCitations(ActivityQueryResult.Page<ActivityQueryResult.Citation> page) {
        return RecentCitationListDTO.builder()
                .items(page.items().stream().map(this::toCitation).toList())
                .nextCursor(page.nextCursor()).build();
    }

    public RecentSearchListDTO toSearches(ActivityQueryResult.Page<ActivityQueryResult.Search> page) {
        return RecentSearchListDTO.builder()
                .items(page.items().stream().map(item -> RecentSearchDTO.builder()
                        .query(item.query()).kbIds(item.kbIds()).knowledgeBaseNames(item.knowledgeBaseNames())
                        .total(item.total()).searchedAt(item.searchedAt()).assetTypes(item.assetTypes())
                        .dateRange(item.dateRange() == null ? null : RecentSearchDTO.DateRange.builder()
                                .from(item.dateRange().from()).to(item.dateRange().to()).build())
                        .withAnswer(item.withAnswer()).answerMode(item.answerMode()).build()).toList())
                .nextCursor(page.nextCursor()).build();
    }

    public RecentDocumentListDTO toDocuments(ActivityQueryResult.Page<ActivityQueryResult.Document> page) {
        return RecentDocumentListDTO.builder()
                .items(page.items().stream().map(item -> RecentDocumentDTO.builder()
                        .taskId(item.taskId()).kbId(item.kbId()).knowledgeBaseName(item.knowledgeBaseName())
                        .status(item.status()).totalCount(item.totalCount()).successCount(item.successCount())
                        .failureCount(item.failureCount()).runningCount(item.runningCount())
                        .importedAt(item.importedAt()).build()).toList())
                .nextCursor(page.nextCursor()).build();
    }

    public RecentCitationDTO toCitation(ActivityQueryResult.Citation item) {
        return RecentCitationDTO.builder()
                .recordId(item.recordId()).segmentId(item.segmentId()).assetId(item.assetId())
                .kbId(item.kbId()).kbName(item.kbName()).fileName(item.fileName()).title(item.title())
                .snippet(item.snippet()).citationReason(item.citationReason()).openedAt(item.openedAt())
                .sourceType(item.sourceType()).sourceId(item.sourceId()).sessionId(item.sessionId())
                .citationIndex(item.citationIndex()).question(item.question()).anchor(toAnchor(item.anchor()))
                .chunks(item.chunks().stream().map(this::toChunk).toList()).build();
    }

    private PreviewAnchorDTO toAnchor(ActivityAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        return PreviewAnchorDTO.builder()
                .pageNo(anchor.pageNo()).chunkOrder(anchor.chunkOrder())
                .bbox(anchor.bbox().stream().map(this::toBbox).toList())
                .imageWidth(anchor.imageWidth()).imageHeight(anchor.imageHeight()).build();
    }

    private BboxInfo toBbox(ActivityAnchor.ActivityBbox value) {
        ActivityAnchor.Bbox box = value.bbox();
        BboxInfo.Bbox dtoBox = box == null ? null : BboxInfo.Bbox.builder()
                .l(box.l()).t(box.t()).r(box.r()).b(box.b()).coordOrigin(box.coordOrigin()).build();
        return BboxInfo.builder().bbox(dtoBox).pageNo(value.pageNo()).build();
    }

    private CitationChunkSnapshotDTO toChunk(ActivityCitationChunk chunk) {
        return CitationChunkSnapshotDTO.builder()
                .segmentId(chunk.segmentId()).segmentIndex(chunk.segmentIndex())
                .citationLabel(chunk.citationLabel()).title(chunk.title()).pageNo(chunk.pageNo())
                .chunkOrder(chunk.chunkOrder()).content(chunk.content()).snippet(chunk.snippet())
                .hitType(chunk.hitType()).anchor(toAnchor(chunk.anchor())).why(toWhy(chunk.why())).build();
    }

    private CitationChunkSnapshotDTO.WhyDTO toWhy(ActivityCitationChunk.Why why) {
        if (why == null) {
            return null;
        }
        return CitationChunkSnapshotDTO.WhyDTO.builder()
                .score(why.score()).hitSources(why.hitSources()).matchedBy(toMatchedBy(why.matchedBy()))
                .matchSummary(why.matchSummary()).reason(why.reason()).build();
    }

    private CitationChunkSnapshotDTO.MatchedByDTO toMatchedBy(ActivityCitationChunk.MatchedBy value) {
        if (value == null) {
            return null;
        }
        return CitationChunkSnapshotDTO.MatchedByDTO.builder()
                .vector(value.vector()).title(value.title()).content(value.content()).ocr(value.ocr()).build();
    }
}
