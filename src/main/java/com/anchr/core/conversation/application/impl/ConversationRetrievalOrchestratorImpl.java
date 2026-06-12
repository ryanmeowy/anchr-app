package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.domain.model.Bbox;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Default retrieval orchestrator for conversation flow.
 */
@Service
@RequiredArgsConstructor
public class ConversationRetrievalOrchestratorImpl implements ConversationRetrievalOrchestrator {

    private static final String MODALITY_TEXT = "TEXT";
    private static final String MODALITY_IMAGE = "IMAGE";
    private static final String MODALITY_MIXED = "MIXED";

    private final UnifiedSearchService unifiedSearchService;
    private final MeterRegistry meterRegistry;

    @Override
    public ConversationRetrievalResult retrieve(String rewrittenQuery,
                                                Integer topK,
                                                Integer limit,
                                                String strategy,
                                                List<String> kbIds,
                                                List<String> preferredModalities) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SearchQueryDTO query = new SearchQueryDTO();
            query.setQuery(rewrittenQuery);
            query.setTopK(topK);
            query.setLimit(limit);
            query.setStrategy(strategy);
            query.setKbIds(kbIds);

            List<SearchResultDTO> rawResults = unifiedSearchService.search(query);
            List<SearchResultDTO> filtered = applyModalityFilter(rawResults, preferredModalities);
            List<ConversationRetrievalCandidate> candidates = filtered.stream()
                    .map(this::toCandidate)
                    .filter(Objects::nonNull)
                    .toList();
            ConversationRetrievalResult result = new ConversationRetrievalResult();
            result.setTopCandidates(candidates);
            result.setGroupedResults(groupByResultType(candidates));
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

    private List<SearchResultDTO> applyModalityFilter(List<SearchResultDTO> rawResults, List<String> preferredModalities) {
        if (rawResults == null || rawResults.isEmpty()) {
            return List.of();
        }
        if (!hasStrictModality(preferredModalities)) {
            return rawResults;
        }
        boolean textOnly = preferredModalities.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .allMatch(MODALITY_TEXT::equals);
        boolean imageOnly = preferredModalities.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .allMatch(MODALITY_IMAGE::equals);

        if (!textOnly && !imageOnly) {
            return rawResults;
        }
        List<SearchResultDTO> filtered = new ArrayList<>();
        for (SearchResultDTO item : rawResults) {
            String segmentType = item == null ? null : item.getSegmentType();
            if (textOnly && isTextSegment(segmentType)) {
                filtered.add(item);
                continue;
            }
            if (imageOnly && isImageSegment(segmentType)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private boolean hasStrictModality(List<String> preferredModalities) {
        if (preferredModalities == null || preferredModalities.isEmpty()) {
            return false;
        }
        for (String modality : preferredModalities) {
            if (!StringUtils.hasText(modality)) {
                continue;
            }
            String value = modality.trim().toUpperCase(Locale.ROOT);
            if (MODALITY_MIXED.equals(value)) {
                return false;
            }
            if (MODALITY_TEXT.equals(value) || MODALITY_IMAGE.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private List<ConversationRetrievalResult.GroupedResult> groupByResultType(List<ConversationRetrievalCandidate> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, List<ConversationRetrievalCandidate>> grouped = new LinkedHashMap<>();
        grouped.put(MODALITY_TEXT, new ArrayList<>());
        grouped.put(MODALITY_IMAGE, new ArrayList<>());
        for (ConversationRetrievalCandidate item : items) {
            String groupKey = isTextSegment(item == null ? null : item.getSegmentType()) ? MODALITY_TEXT : MODALITY_IMAGE;
            grouped.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(item);
        }
        List<ConversationRetrievalResult.GroupedResult> results = new ArrayList<>();
        for (Map.Entry<String, List<ConversationRetrievalCandidate>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            ConversationRetrievalResult.GroupedResult groupedResult = new ConversationRetrievalResult.GroupedResult();
            groupedResult.setGroupKey(entry.getKey());
            groupedResult.setItems(entry.getValue());
            results.add(groupedResult);
        }
        return results;
    }

    private ConversationRetrievalCandidate toCandidate(SearchResultDTO item) {
        if (item == null) {
            return null;
        }
        return ConversationRetrievalCandidate.builder()
                .segmentId(item.getSegmentId())
                .assetId(item.getAssetId())
                .assetType(item.getAssetType())
                .resultType(item.getResultType())
                .segmentType(item.getSegmentType())
                .sourceRef(item.getSourceRef())
                .snippet(item.getSnippet())
                .score(item.getScore())
                .pageNo(item.getPageNo())
                .anchor(toCandidateAnchor(item.getAnchor()))
                .topChunks(toCandidateTopChunks(item.getTopChunks()))
                .explain(toCandidateExplain(item.getExplain()))
                .build();
    }

    private ConversationRetrievalCandidate.Anchor toCandidateAnchor(SearchResultDTO.Anchor source) {
        if (source == null) {
            return null;
        }
        return ConversationRetrievalCandidate.Anchor.builder()
                .pageNo(source.getPageNo())
                .chunkOrder(source.getChunkOrder())
                .bbox(toCandidateBbox(source.getBbox()))
                .imageWidth(source.getImageWidth())
                .imageHeight(source.getImageHeight())
                .build();
    }

    private ConversationRetrievalCandidate.Bbox toCandidateBbox(Bbox source) {
        if (source == null) {
            return null;
        }
        return ConversationRetrievalCandidate.Bbox.builder()
                .x(source.getX())
                .y(source.getY())
                .width(source.getWidth())
                .height(source.getHeight())
                .unit(source.getUnit())
                .build();
    }

    private List<ConversationRetrievalCandidate.TopChunk> toCandidateTopChunks(List<SearchResultDTO.TopChunk> topChunks) {
        if (topChunks == null || topChunks.isEmpty()) {
            return List.of();
        }
        List<ConversationRetrievalCandidate.TopChunk> candidates = new ArrayList<>();
        for (SearchResultDTO.TopChunk topChunk : topChunks) {
            if (topChunk == null) {
                continue;
            }
            candidates.add(ConversationRetrievalCandidate.TopChunk.builder()
                    .snippet(topChunk.getSnippet())
                    .build());
        }
        return candidates;
    }

    private ConversationRetrievalCandidate.Explain toCandidateExplain(SearchExplainDTO source) {
        if (source == null) {
            return null;
        }
        return ConversationRetrievalCandidate.Explain.builder()
                .strategyEffective(source.getStrategyEffective())
                .hitSources(source.getHitSources() == null ? List.of() : List.copyOf(source.getHitSources()))
                .build();
    }

    private boolean isTextSegment(String segmentType) {
        return StringUtils.hasText(segmentType) && segmentType.toUpperCase(Locale.ROOT).startsWith("TEXT");
    }

    private boolean isImageSegment(String segmentType) {
        return StringUtils.hasText(segmentType) && segmentType.toUpperCase(Locale.ROOT).startsWith("IMAGE");
    }
}
