package com.anchr.core.search.interfaces.rest.assembler;

import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalFacet;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalInsight;
import com.anchr.core.search.application.api.model.RetrievalTopNQuery;
import com.anchr.core.search.application.api.model.RetrievalTopNResult;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.application.api.model.SearchAnswerResult;
import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.search.interfaces.rest.dto.RetrievalInsightDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchAnswerDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchTopNDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps the public REST contract outside Retrieval application APIs. */
@Component
public class SearchRestAssembler {

    public RetrievalTopNQuery toTopNQuery(SearchQueryDTO source, SearchRewriteResult rewrite) {
        String rewritten = rewrite == null ? null : rewrite.getRewrittenQuery();
        String effectiveQuery = StringUtils.hasText(rewritten) ? rewritten : source.getQuery();
        return new RetrievalTopNQuery(
                effectiveQuery,
                rewrite == null ? List.of() : rewrite.getKeywords(),
                source.getLimit(), source.getKbIds(), source.getAssetIdList(), source.getAssetTypes(),
                source.getHitTypes(),
                source.getDateRange() == null ? null : source.getDateRange().getFrom(),
                source.getDateRange() == null ? null : source.getDateRange().getTo());
    }

    public SearchTopNDTO toTopNDto(RetrievalTopNResult source,
                                  SearchRewriteResult rewrite,
                                  SearchAnswerResult answer,
                                  List<String> suggestedQuestions) {
        return SearchTopNDTO.builder()
                .items(source.items().stream().map(this::toResultDto).toList())
                .returnedCount(source.items().size())
                .windowFacets(toFacetDtos(source.windowFacets()))
                .answer(toAnswerDto(answer))
                .rewrittenQuery(rewrite == null ? null : rewrite.getRewrittenQuery())
                .rewrittenKeywords(rewrite == null ? List.of() : rewrite.getKeywords())
                .insight(toInsightDto(source.insight(), rewrite))
                .suggestedQuestions(suggestedQuestions == null ? List.of() : List.copyOf(suggestedQuestions))
                .build();
    }

    public SearchResultDTO toResultDto(RetrievalHit source) {
        if (source == null) return null;
        return SearchResultDTO.builder()
                .segmentType(source.segmentType()).title(source.title()).content(source.content())
                .resultType(source.resultType()).assetType(source.assetType()).snippet(source.snippet())
                .pageNo(source.pageNo()).score(source.score()).explain(toExplainDto(source.explain()))
                .anchor(toAnchorDto(source.anchor())).thumbnail(source.thumbnail()).ocrSummary(source.ocrSummary())
                .totalHits(source.totalHits())
                .topChunks(source.topChunks().stream().map(this::toTopChunkDto).toList())
                .segmentId(source.segmentId()).kbId(source.kbId()).assetId(source.assetId())
                .sourceRef(source.sourceRef()).imagePreviewUrl(source.imagePreviewUrl())
                .imagePreviewExpiresAt(source.imagePreviewExpiresAt())
                .build();
    }

    public SearchAnswerDTO toAnswerDto(SearchAnswerResult source) {
        if (source == null) return null;
        return SearchAnswerDTO.builder()
                .answer(source.answer())
                .citations(source.citations().stream().map(this::toCitationDto).toList())
                .results(source.results().stream().map(this::toResultDto).toList())
                .answerTrace(source.answerTrace() == null ? null : SearchAnswerDTO.AnswerTraceDTO.builder()
                        .mode(source.answerTrace().mode()).grounded(source.answerTrace().grounded())
                        .fallbackReason(source.answerTrace().fallbackReason()).build())
                .build();
    }

    private SearchAnswerDTO.CitationDTO toCitationDto(SearchAnswerResult.Citation source) {
        return SearchAnswerDTO.CitationDTO.builder()
                .citationIndex(source.citationIndex()).assetId(source.assetId()).kbId(source.kbId())
                .fileName(source.fileName()).chunks(source.chunks().stream().map(this::toCitationChunkDto).toList())
                .build();
    }

    private SearchAnswerDTO.CitationChunkDTO toCitationChunkDto(SearchAnswerResult.CitationChunk source) {
        return SearchAnswerDTO.CitationChunkDTO.builder()
                .segmentId(source.segmentId()).pageNo(source.pageNo()).chunkOrder(source.chunkOrder())
                .title(source.title()).content(source.content()).snippet(source.snippet())
                .anchor(toAnchorDto(source.anchor())).why(toCitationWhyDto(source.why())).build();
    }

    private SearchAnswerDTO.CitationWhy toCitationWhyDto(SearchAnswerResult.CitationWhy source) {
        if (source == null) return null;
        SearchAnswerDTO.CitationWhy.MatchedBy matchedBy = source.matchedBy() == null ? null
                : SearchAnswerDTO.CitationWhy.MatchedBy.builder()
                .vector(source.matchedBy().vector()).title(source.matchedBy().title())
                .content(source.matchedBy().content()).ocr(source.matchedBy().ocr()).build();
        return SearchAnswerDTO.CitationWhy.builder()
                .score(source.score()).hitSources(source.hitSources()).matchedBy(matchedBy)
                .matchSummary(source.matchSummary()).reason(source.reason()).build();
    }

    private SearchResultDTO.TopChunk toTopChunkDto(RetrievalTopChunk source) {
        return SearchResultDTO.TopChunk.builder()
                .segmentId(source.segmentId()).kbId(source.kbId()).segmentType(source.segmentType())
                .title(source.title()).content(source.content()).snippet(source.snippet())
                .explain(toExplainDto(source.explain())).score(source.score()).pageNo(source.pageNo())
                .anchor(toAnchorDto(source.anchor())).sourceRef(source.sourceRef())
                .imagePreviewUrl(source.imagePreviewUrl()).imagePreviewExpiresAt(source.imagePreviewExpiresAt())
                .thumbnail(source.thumbnail()).ocrSummary(source.ocrSummary()).build();
    }

    private SearchResultDTO.Anchor toAnchorDto(RetrievalAnchor source) {
        if (source == null) return null;
        return SearchResultDTO.Anchor.builder()
                .pageNo(source.pageNo()).chunkOrder(source.chunkOrder()).bbox(source.bbox())
                .imageWidth(source.imageWidth()).imageHeight(source.imageHeight()).build();
    }

    private SearchExplainDTO toExplainDto(RetrievalExplain source) {
        if (source == null) return null;
        SearchExplainDTO.MatchedBy matchedBy = source.matchedBy() == null ? null
                : SearchExplainDTO.MatchedBy.builder()
                .vector(source.matchedBy().vector()).title(source.matchedBy().title())
                .content(source.matchedBy().content()).ocr(source.matchedBy().ocr()).build();
        SearchExplainDTO.TextSignals textSignals = source.textSignals() == null ? null
                : SearchExplainDTO.TextSignals.builder()
                .semantic(source.textSignals().semantic()).keyword(source.textSignals().keyword())
                .pageHit(source.textSignals().pageHit()).chunkHit(source.textSignals().chunkHit()).build();
        SearchExplainDTO.ImageSignals imageSignals = source.imageSignals() == null ? null
                : SearchExplainDTO.ImageSignals.builder()
                .vector(source.imageSignals().vector()).ocr(source.imageSignals().ocr())
                .caption(source.imageSignals().caption()).tag(source.imageSignals().tag()).build();
        return SearchExplainDTO.builder().hitSources(source.hitSources()).matchedBy(matchedBy)
                .textSignals(textSignals).imageSignals(imageSignals).build();
    }

    private Map<String, List<SearchTopNDTO.FacetItemDTO>> toFacetDtos(
            Map<String, List<RetrievalFacet>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, List<SearchTopNDTO.FacetItemDTO>> result = new LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, values.stream()
                .map(value -> SearchTopNDTO.FacetItemDTO.builder()
                        .value(value.value()).count(value.count()).build()).toList()));
        return result;
    }

    private RetrievalInsightDTO toInsightDto(RetrievalInsight source, SearchRewriteResult rewrite) {
        if (source == null) return null;
        return RetrievalInsightDTO.builder()
                .pipeline(source.pipeline() == null ? null : RetrievalInsightDTO.PipelineDTO.builder()
                        .keywordCandidates(source.pipeline().keywordCandidates())
                        .vectorCandidates(source.pipeline().vectorCandidates())
                        .fusedRetained(source.pipeline().fusedRetained())
                        .rerankAdopted(source.pipeline().rerankAdopted()).build())
                .relevanceDistribution(source.relevanceDistribution() == null ? null
                        : RetrievalInsightDTO.RelevanceDistributionDTO.builder()
                        .high(source.relevanceDistribution().high()).medium(source.relevanceDistribution().medium())
                        .low(source.relevanceDistribution().low()).build())
                .risk(source.risk() == null ? null : RetrievalInsightDTO.RiskDTO.builder()
                        .lowRelevanceCount(source.risk().lowRelevanceCount()).build())
                .hitSourceDistribution(source.hitSourceDistribution() == null ? null
                        : RetrievalInsightDTO.HitSourceDistributionDTO.builder()
                        .vectorCount(source.hitSourceDistribution().vectorCount())
                        .contentCount(source.hitSourceDistribution().contentCount())
                        .ocrCount(source.hitSourceDistribution().ocrCount())
                        .tagCount(source.hitSourceDistribution().tagCount())
                        .titleCount(source.hitSourceDistribution().titleCount()).build())
                .queryIntent(rewrite == null ? null : RetrievalInsightDTO.QueryIntentDTO.builder()
                        .intent(rewrite.getIntent()).category(rewrite.getIntentCategory())
                        .fallback(rewrite.isFallbackUsed()).build())
                .latencyMs(source.latencyMs()).build();
    }
}
