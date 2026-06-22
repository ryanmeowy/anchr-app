package com.anchr.core.conversation.application.assembler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.conversation.application.model.RewriteResult;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConversationRetrievalTraceBuilder {

    private final ObjectMapper objectMapper;

    public String buildTraceJson(ConversationMessageRequestDTO request,
                                 RewriteResult rewriteResult,
                                 ConversationRetrievalResult retrievalResult,
                                 AnswerGenerationResult answerGenerationResult) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("limit", request.getLimit());
        trace.put("kbIds", request.getKbIds());
        trace.put("answerMode", request.getAnswerMode());
        trace.put("rewriteReason", rewriteResult.getRewriteReason());
        trace.put("topicEntities", rewriteResult.getTopicEntities());
        trace.put("preferredModalities", rewriteResult.getPreferredModalities());
        trace.put("rewriteConfidence", rewriteResult.getConfidence());
        trace.put("rewriteFallback", rewriteResult.isFallbackUsed());
        trace.put("retrievedCount", retrievalResult.getTopCandidates().size());
        trace.put("retrievedSegmentIds", extractTopSegmentIds(retrievalResult, 20));
        trace.put("groupedResultCounts", toGroupedCounts(retrievalResult));
        trace.put("answerInputSegmentIds", answerGenerationResult.getAnswerInputSegmentIds());
        trace.put("answerFallback", answerGenerationResult.isFallbackUsed());
        trace.put("answerFallbackReason", answerGenerationResult.getFallbackReason());
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize retrieval trace.", e);
        }
    }

    public ConversationMessageResponseDTO.RetrievalTraceDTO buildTraceDto(ConversationMessageRequestDTO request,
                                                                          RewriteResult rewriteResult,
                                                                          ConversationRetrievalResult retrievalResult,
                                                                          AnswerGenerationResult answerGenerationResult) {
        ConversationMessageResponseDTO.RetrievalTraceDTO traceDTO = new ConversationMessageResponseDTO.RetrievalTraceDTO();
        traceDTO.setLimit(request.getLimit());
        traceDTO.setStrategyEffective(resolveStrategyEffective(retrievalResult));
        traceDTO.setRewriteReason(rewriteResult.getRewriteReason());
        traceDTO.setRewriteConfidence(rewriteResult.getConfidence());
        traceDTO.setRewriteFallback(rewriteResult.isFallbackUsed());
        traceDTO.setRetrievedCount(retrievalResult.getTopCandidates().size());
        traceDTO.setGroupedResultCounts(toGroupedCounts(retrievalResult));
        traceDTO.setTopSegmentIds(extractTopSegmentIds(retrievalResult, 5));
        traceDTO.setTopHitSources(extractTopHitSources(retrievalResult, 6));
        traceDTO.setAnswerFallback(answerGenerationResult.isFallbackUsed());
        traceDTO.setAnswerFallbackReason(answerGenerationResult.getFallbackReason());
        return traceDTO;
    }

    private Map<String, Integer> toGroupedCounts(ConversationRetrievalResult retrievalResult) {
        Map<String, Integer> groupedCounts = new LinkedHashMap<>();
        if (retrievalResult.getGroupedResults() == null || retrievalResult.getGroupedResults().isEmpty()) {
            return groupedCounts;
        }
        for (ConversationRetrievalResult.GroupedResult groupedResult : retrievalResult.getGroupedResults()) {
            if (groupedResult == null || !StringUtils.hasText(groupedResult.getGroupKey())) {
                continue;
            }
            groupedCounts.put(groupedResult.getGroupKey(), groupedResult.getItems() == null ? 0 : groupedResult.getItems().size());
        }
        return groupedCounts;
    }

    private List<String> extractTopSegmentIds(ConversationRetrievalResult retrievalResult, int limit) {
        if (retrievalResult.getTopCandidates() == null || retrievalResult.getTopCandidates().isEmpty()) {
            return List.of();
        }
        return retrievalResult.getTopCandidates().stream()
                .map(this::safeSegmentId)
                .filter(StringUtils::hasText)
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<String> extractTopHitSources(ConversationRetrievalResult retrievalResult, int limit) {
        if (retrievalResult.getTopCandidates() == null || retrievalResult.getTopCandidates().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> hitSources = new LinkedHashSet<>();
        for (ConversationRetrievalCandidate candidate : retrievalResult.getTopCandidates()) {
            if (candidate == null || candidate.getExplain() == null || candidate.getExplain().getHitSources() == null) {
                continue;
            }
            for (String source : candidate.getExplain().getHitSources()) {
                if (StringUtils.hasText(source)) {
                    hitSources.add(source.trim());
                }
            }
        }
        if (hitSources.isEmpty()) {
            return List.of();
        }
        return hitSources.stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    private String resolveStrategyEffective(ConversationRetrievalResult retrievalResult) {
        if (retrievalResult.getTopCandidates() == null || retrievalResult.getTopCandidates().isEmpty()) {
            return null;
        }
        for (ConversationRetrievalCandidate candidate : retrievalResult.getTopCandidates()) {
            if (candidate == null || candidate.getExplain() == null) {
                continue;
            }
            String strategyEffective = candidate.getExplain().getStrategyEffective();
            if (StringUtils.hasText(strategyEffective)) {
                return strategyEffective.trim();
            }
        }
        return null;
    }

    private String safeSegmentId(ConversationRetrievalCandidate item) {
        return item == null ? null : item.getSegmentId();
    }
}
