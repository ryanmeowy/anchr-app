package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Default retrieval orchestrator for conversation flow.
 */
@Service
@RequiredArgsConstructor
public class ConversationRetrievalOrchestratorImpl implements ConversationRetrievalOrchestrator {

    private static final String MODALITY_MIXED = "MIXED";

    private final UnifiedSearchService unifiedSearchService;
    private final MeterRegistry meterRegistry;

    @Override
    public ConversationRetrievalResult retrieve(String rewrittenQuery,
                                                Integer limit,
                                                List<String> kbIds,
                                                List<String> preferredModalities,
                                                List<String> assetIdList) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SearchQueryDTO query = new SearchQueryDTO();
            query.setQuery(rewrittenQuery);
            query.setLimit(limit);
            query.setKbIds(kbIds);
            query.setAssetIdList(assetIdList);
            query.setHitTypes(resolveSegmentTypes(preferredModalities));

            List<SearchResultDTO> rawResults = unifiedSearchService.search(query);
            List<ConversationRetrievalCandidate> candidates = rawResults.stream()
                    .flatMap(item -> toCandidates(item).stream())
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(
                            candidate -> candidate.getScore() == null ? 0.0D : candidate.getScore(),
                            Comparator.reverseOrder()))
                    .toList();
            ConversationRetrievalResult result = new ConversationRetrievalResult();
            result.setTopCandidates(candidates);
            meterRegistry.summary("conversation.retrieval.topk").record(candidates.size());
            if (candidates.isEmpty()) {
                meterRegistry.counter("conversation.retrieval.empty.count").increment();
            }
            return result;
        } finally {
            sample.stop(Timer.builder("conversation.retrieval.latency")
                    .description("Conversation retrieval orchestrator latency.")
                    .register(meterRegistry));
        }
    }

    private List<String> resolveSegmentTypes(List<String> preferredModalities) {
        if (preferredModalities == null || preferredModalities.isEmpty()) {
            return List.of();
        }
        List<String> normalized = preferredModalities.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (normalized.size() != 1 || MODALITY_MIXED.equals(normalized.getFirst())) {
            return List.of();
        }
        String prefix = normalized.getFirst();
        return Arrays.stream(SegmentType.values())
                .map(Enum::name)
                .filter(segmentType -> segmentType.startsWith(prefix))
                .toList();
    }

    private List<ConversationRetrievalCandidate> toCandidates(SearchResultDTO item) {
        if (item == null) {
            return List.of();
        }
        if (item.getTopChunks() == null || item.getTopChunks().isEmpty()) {
            return List.of(toCandidate(item, null));
        }
        return item.getTopChunks().stream()
                .filter(Objects::nonNull)
                .map(topChunk -> toCandidate(item, topChunk))
                .toList();
    }

    private ConversationRetrievalCandidate toCandidate(SearchResultDTO item, SearchResultDTO.TopChunk topChunk) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(topChunk == null ? item.getSegmentId() : topChunk.getSegmentId())
                .kbId(topChunk == null || !StringUtils.hasText(topChunk.getKbId()) ? item.getKbId() : topChunk.getKbId())
                .assetId(item.getAssetId())
                .assetType(item.getAssetType())
                .resultType(item.getResultType())
                .segmentType(topChunk == null ? item.getSegmentType() : topChunk.getSegmentType())
                .title(topChunk == null ? item.getTitle() : topChunk.getTitle())
                .sourceRef(topChunk == null || !StringUtils.hasText(topChunk.getSourceRef())
                        ? item.getSourceRef() : topChunk.getSourceRef())
                .content(topChunk == null ? item.getContent() : topChunk.getContent())
                .snippet(topChunk == null ? item.getSnippet() : topChunk.getSnippet())
                .score(topChunk == null ? item.getScore() : topChunk.getScore())
                .pageNo(topChunk == null ? item.getPageNo() : topChunk.getPageNo())
                .anchor(toCandidateAnchor(topChunk == null ? item.getAnchor() : topChunk.getAnchor()))
                .explain(toCandidateExplain(topChunk == null
                        ? item.getExplain() : topChunk.getExplain()))
                .build();
    }

    private ConversationRetrievalCandidate.Anchor toCandidateAnchor(SearchResultDTO.Anchor source) {
        if (source == null) {
            return null;
        }
        return ConversationRetrievalCandidate.Anchor.builder()
                .pageNo(source.getPageNo())
                .chunkOrder(source.getChunkOrder())
                .bbox(source.getBbox())
                .imageWidth(source.getImageWidth())
                .imageHeight(source.getImageHeight())
                .build();
    }

    private ConversationRetrievalCandidate.Explain toCandidateExplain(SearchExplainDTO source) {
        if (source == null) {
            return null;
        }
        return ConversationRetrievalCandidate.Explain.builder()
//                .strategyEffective(source.getStrategyEffective())
                .hitSources(source.getHitSources() == null ? List.of() : List.copyOf(source.getHitSources()))
                .build();
    }

}
