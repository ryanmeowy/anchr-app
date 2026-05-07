package com.smart.vision.core.conversation.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.conversation.application.AnswerGenerationService;
import com.smart.vision.core.conversation.application.FollowUpQuestionService;
import com.smart.vision.core.conversation.application.QueryRewriteService;
import com.smart.vision.core.conversation.application.ConversationService;
import com.smart.vision.core.conversation.application.ConversationRetrievalOrchestrator;
import com.smart.vision.core.conversation.application.assembler.ConversationCitationMapper;
import com.smart.vision.core.conversation.application.assembler.ConversationResultCardMapper;
import com.smart.vision.core.conversation.application.model.AnswerGenerationResult;
import com.smart.vision.core.conversation.application.model.ConversationRetrievalResult;
import com.smart.vision.core.conversation.application.model.RewriteResult;
import com.smart.vision.core.conversation.domain.model.ConversationCitation;
import com.smart.vision.core.conversation.domain.model.ConversationRole;
import com.smart.vision.core.conversation.domain.model.ConversationSession;
import com.smart.vision.core.conversation.domain.model.ConversationTurn;
import com.smart.vision.core.conversation.domain.repository.ConversationRepository;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ResultCardDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ResultHitDTO;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchResultDTO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default conversation application service.
 */
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final int DEFAULT_TURN_LIMIT = 20;
    private static final int MAX_TURN_LIMIT = 100;
    private static final int ANSWER_CITATION_LIMIT = 5;
    private static final int AUTO_TITLE_MAX_LENGTH = 128;

    private final ConversationRepository conversationRepository;
    private final QueryRewriteService queryRewriteService;
    private final ConversationRetrievalOrchestrator conversationRetrievalOrchestrator;
    private final ConversationCitationMapper conversationCitationMapper;
    private final ConversationResultCardMapper conversationResultCardMapper;
    private final AnswerGenerationService answerGenerationService;
    private final FollowUpQuestionService followUpQuestionService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public ConversationSessionDTO createSession(ConversationCreateRequestDTO request) {
        long now = System.currentTimeMillis();
        String title = safeTrim(request.getTitle());
        ConversationSession session = ConversationSession.createActive(newSessionId(), title, now);
        conversationRepository.saveSession(session);
        meterRegistry.counter("conversation.created.count").increment();
        return toSessionDto(session);
    }

    @Override
    public ConversationSessionDTO getSession(String sessionId) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        return toSessionDto(session);
    }

    @Override
    public ConversationMessageResponseDTO createMessage(String sessionId, ConversationMessageRequestDTO request) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        long now = System.currentTimeMillis();
        boolean shouldAutoTitle = shouldAutoGenerateTitle(session.getSessionId(), session.getTitle());
        meterRegistry.counter("conversation.active.count").increment();
        MessagePipelineResult pipelineResult = executeMessagePipeline(session.getSessionId(), request);

        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId(newTurnId());
        turn.setSessionId(session.getSessionId());
        turn.setRole(ConversationRole.USER);
        turn.setQuery(request.getQuery().trim());
        turn.setRewrittenQuery(pipelineResult.rewriteResult.getRewrittenQuery());
        turn.setAnswer(pipelineResult.answerGenerationResult.getAnswerText());
        turn.setCitationsJson(serializeCitations(pipelineResult.answerCitations));
        turn.setResultCardsJson(serializeResultCards(pipelineResult.resultCards));
        turn.setRetrievalTraceJson(buildRetrievalTraceJson(
                request,
                pipelineResult.rewriteResult,
                pipelineResult.retrievalResult,
                pipelineResult.answerGenerationResult
        ));
        turn.setCreatedAt(now);
        conversationRepository.saveTurn(turn);
        meterRegistry.counter("conversation.turn.count").increment();

        if (shouldAutoTitle) {
            session.setTitle(buildAutoTitle(request.getQuery(), pipelineResult.rewriteResult.getRewrittenQuery()));
        }
        session.touch(now);
        conversationRepository.saveSession(session);

        ConversationMessageResponseDTO response = new ConversationMessageResponseDTO();
        response.setSessionId(session.getSessionId());
        response.setTurnId(turn.getTurnId());
        response.setRewrittenQuery(pipelineResult.rewriteResult.getRewrittenQuery());
        response.setAnswer(turn.getAnswer());
        response.setCitations(toCitationDTOs(pipelineResult.answerCitations));
        response.setResultCards(pipelineResult.resultCards);
        List<String> suggestedQuestions = followUpQuestionService.generate(
                request.getQuery().trim(),
                pipelineResult.rewriteResult.getRewrittenQuery(),
                pipelineResult.answerCitations
        );
        response.setSuggestedQuestions(suggestedQuestions);

        ConversationMessageResponseDTO.RetrievalTraceDTO traceDTO = new ConversationMessageResponseDTO.RetrievalTraceDTO();
        traceDTO.setTopK(request.getTopK());
        traceDTO.setLimit(request.getLimit());
        traceDTO.setStrategy(request.getStrategy());
        traceDTO.setStrategyEffective(resolveStrategyEffective(pipelineResult.retrievalResult, request.getStrategy()));
        traceDTO.setRewriteReason(pipelineResult.rewriteResult.getRewriteReason());
        traceDTO.setRewriteConfidence(pipelineResult.rewriteResult.getConfidence());
        traceDTO.setRewriteFallback(pipelineResult.rewriteResult.isFallbackUsed());
        traceDTO.setRetrievedCount(pipelineResult.retrievalResult.getTopCandidates().size());
        traceDTO.setGroupedResultCounts(toGroupedCounts(pipelineResult.retrievalResult));
        traceDTO.setTopSegmentIds(extractTopSegmentIds(pipelineResult.retrievalResult, 5));
        traceDTO.setTopHitSources(extractTopHitSources(pipelineResult.retrievalResult, 6));
        traceDTO.setAnswerFallback(pipelineResult.answerGenerationResult.isFallbackUsed());
        traceDTO.setAnswerFallbackReason(pipelineResult.answerGenerationResult.getFallbackReason());
        response.setRetrievalTrace(traceDTO);
        response.setCreatedAt(now);
        meterRegistry.summary("answer.citation.count").record(pipelineResult.answerCitations.size());
        if (pipelineResult.answerCitations.isEmpty()) {
            meterRegistry.counter("answer.citation.empty.count").increment();
        }
        return response;
    }

    private MessagePipelineResult executeMessagePipeline(String sessionId, ConversationMessageRequestDTO request) {
        RewriteResult rewriteResult = queryRewriteService.rewrite(sessionId, request.getQuery().trim());
        ConversationRetrievalResult retrievalResult = conversationRetrievalOrchestrator.retrieve(
                rewriteResult.getRewrittenQuery(),
                request.getTopK(),
                request.getLimit(),
                request.getStrategy(),
                rewriteResult.getPreferredModalities()
        );
        List<ResultCardDTO> resultCards = conversationResultCardMapper.map(retrievalResult.getTopCandidates());
        LinkedHashSet<String> resultCardSegmentIds = collectResultCardSegmentIds(resultCards);
        List<KbSearchResultDTO> answerCandidates = retrievalResult.getTopCandidates()
                .stream()
                .filter(candidate -> isTraceableCandidate(candidate, resultCardSegmentIds))
                .limit(ANSWER_CITATION_LIMIT)
                .toList();
        List<ConversationCitation> answerCitations = conversationCitationMapper.mapFromSearchResults(answerCandidates);
        AnswerGenerationResult answerGenerationResult = answerGenerationService.generate(
                request.getQuery().trim(),
                rewriteResult.getRewrittenQuery(),
                answerCandidates,
                answerCitations
        );
        return new MessagePipelineResult(rewriteResult, retrievalResult, resultCards, answerCitations, answerGenerationResult);
    }

    private LinkedHashSet<String> collectResultCardSegmentIds(List<ResultCardDTO> resultCards) {
        if (resultCards == null || resultCards.isEmpty()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> segmentIds = new LinkedHashSet<>();
        for (ResultCardDTO card : resultCards) {
            if (card == null) {
                continue;
            }
            addHitSegmentId(segmentIds, card.getPrimaryHit());
            if (card.getAdditionalHits() == null || card.getAdditionalHits().isEmpty()) {
                continue;
            }
            for (ResultHitDTO hit : card.getAdditionalHits()) {
                addHitSegmentId(segmentIds, hit);
            }
        }
        return segmentIds;
    }

    private void addHitSegmentId(LinkedHashSet<String> segmentIds, ResultHitDTO hit) {
        if (hit != null && StringUtils.hasText(hit.getSegmentId())) {
            segmentIds.add(hit.getSegmentId().trim());
        }
    }

    private boolean isTraceableCandidate(KbSearchResultDTO candidate, LinkedHashSet<String> resultCardSegmentIds) {
        return candidate != null
                && StringUtils.hasText(candidate.getSegmentId())
                && resultCardSegmentIds.contains(candidate.getSegmentId().trim());
    }

    @Override
    public ConversationTurnListDTO listMessages(String sessionId, Integer limit, String beforeTurnId) {
        ConversationSession session = loadSessionOrThrow(sessionId);
        int boundedLimit = normalizeLimit(limit);
        List<ConversationTurn> candidates = conversationRepository.findRecentTurns(session.getSessionId(), MAX_TURN_LIMIT);
        if (candidates.isEmpty()) {
            ConversationTurnListDTO empty = new ConversationTurnListDTO();
            empty.setSessionId(session.getSessionId());
            return empty;
        }

        List<ConversationTurn> filteredTurns = filterBeforeTurn(candidates, session.getSessionId(), beforeTurnId);
        filteredTurns.sort(Comparator.comparingLong(ConversationTurn::getCreatedAt));
        if (filteredTurns.size() > boundedLimit) {
            filteredTurns = filteredTurns.subList(filteredTurns.size() - boundedLimit, filteredTurns.size());
        }

        ConversationTurnListDTO response = new ConversationTurnListDTO();
        response.setSessionId(session.getSessionId());
        response.setTurns(filteredTurns.stream().map(this::toTurnDto).toList());
        return response;
    }

    private List<ConversationTurn> filterBeforeTurn(List<ConversationTurn> candidates, String sessionId, String beforeTurnId) {
        if (!StringUtils.hasText(beforeTurnId)) {
            return new ArrayList<>(candidates);
        }
        ConversationTurn beforeTurn = conversationRepository.findTurn(sessionId, beforeTurnId)
                .orElseThrow(() -> new IllegalArgumentException("beforeTurnId is invalid"));
        long boundary = beforeTurn.getCreatedAt();
        return candidates.stream()
                .filter(turn -> turn.getCreatedAt() < boundary)
                .toList();
    }

    private ConversationSession loadSessionOrThrow(String sessionId) {
        return conversationRepository.findSession(sessionId)
                .orElseThrow(() -> new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND));
    }

    private ConversationSessionDTO toSessionDto(ConversationSession session) {
        ConversationSessionDTO dto = new ConversationSessionDTO();
        dto.setSessionId(session.getSessionId());
        dto.setTitle(session.getTitle());
        dto.setStatus(session.getStatus().name());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        return dto;
    }

    private ConversationTurnDTO toTurnDto(ConversationTurn turn) {
        ConversationTurnDTO dto = new ConversationTurnDTO();
        dto.setTurnId(turn.getTurnId());
        dto.setSessionId(turn.getSessionId());
        dto.setQuery(turn.getQuery());
        dto.setRewrittenQuery(turn.getRewrittenQuery());
        dto.setAnswer(turn.getAnswer());
        dto.setCitations(parseCitations(turn.getCitationsJson()));
        dto.setResultCards(parseResultCards(turn.getResultCardsJson()));
        dto.setCreatedAt(turn.getCreatedAt());
        return dto;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_TURN_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_TURN_LIMIT));
    }

    private String safeTrim(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }

    private boolean shouldAutoGenerateTitle(String sessionId, String existingTitle) {
        if (StringUtils.hasText(existingTitle)) {
            return false;
        }
        return conversationRepository.findRecentTurns(sessionId, 1).isEmpty();
    }

    private String buildAutoTitle(String query, String rewrittenQuery) {
        String candidate = StringUtils.hasText(rewrittenQuery) ? rewrittenQuery : query;
        if (!StringUtils.hasText(candidate)) {
            return "新会话";
        }
        String normalized = candidate.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= AUTO_TITLE_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, AUTO_TITLE_MAX_LENGTH);
    }

    private String buildRetrievalTraceJson(ConversationMessageRequestDTO request,
                                           RewriteResult rewriteResult,
                                           ConversationRetrievalResult retrievalResult,
                                           AnswerGenerationResult answerGenerationResult) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("topK", request.getTopK());
        trace.put("limit", request.getLimit());
        trace.put("strategy", request.getStrategy());
        trace.put("rewriteReason", rewriteResult.getRewriteReason());
        trace.put("topicEntities", rewriteResult.getTopicEntities());
        trace.put("preferredModalities", rewriteResult.getPreferredModalities());
        trace.put("rewriteConfidence", rewriteResult.getConfidence());
        trace.put("rewriteFallback", rewriteResult.isFallbackUsed());
        trace.put("retrievedCount", retrievalResult.getTopCandidates().size());
        trace.put("retrievedSegmentIds", retrievalResult.getTopCandidates().stream()
                .map(ConversationServiceImpl::safeSegmentId)
                .filter(StringUtils::hasText)
                .limit(20)
                .toList());
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

    private static String safeSegmentId(KbSearchResultDTO item) {
        return item == null ? null : item.getSegmentId();
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
                .map(ConversationServiceImpl::safeSegmentId)
                .filter(StringUtils::hasText)
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<String> extractTopHitSources(ConversationRetrievalResult retrievalResult, int limit) {
        if (retrievalResult.getTopCandidates() == null || retrievalResult.getTopCandidates().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> hitSources = new LinkedHashSet<>();
        for (KbSearchResultDTO candidate : retrievalResult.getTopCandidates()) {
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

    private String resolveStrategyEffective(ConversationRetrievalResult retrievalResult, String fallbackStrategy) {
        if (retrievalResult.getTopCandidates() == null || retrievalResult.getTopCandidates().isEmpty()) {
            return fallbackStrategy;
        }
        for (KbSearchResultDTO candidate : retrievalResult.getTopCandidates()) {
            if (candidate == null || candidate.getExplain() == null) {
                continue;
            }
            String strategyEffective = candidate.getExplain().getStrategyEffective();
            if (StringUtils.hasText(strategyEffective)) {
                return strategyEffective.trim();
            }
        }
        return fallbackStrategy;
    }

    private String newSessionId() {
        return "cvs_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String newTurnId() {
        return "turn_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String serializeCitations(List<ConversationCitation> citations) {
        try {
            return objectMapper.writeValueAsString(citations == null ? List.of() : citations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize citations.", e);
        }
    }

    private String serializeResultCards(List<ResultCardDTO> resultCards) {
        try {
            return objectMapper.writeValueAsString(resultCards == null ? List.of() : resultCards);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize result cards.", e);
        }
    }

    private List<ConversationTurnDTO.CitationDTO> parseCitations(String citationsJson) {
        if (!StringUtils.hasText(citationsJson)) {
            return List.of();
        }
        try {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ConversationCitation.class);
            List<ConversationCitation> citations = objectMapper.readValue(citationsJson, listType);
            return toCitationDTOs(citations);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ResultCardDTO> parseResultCards(String resultCardsJson) {
        if (!StringUtils.hasText(resultCardsJson)) {
            return List.of();
        }
        try {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ResultCardDTO.class);
            return objectMapper.readValue(resultCardsJson, listType);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ConversationTurnDTO.CitationDTO> toCitationDTOs(List<ConversationCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        List<ConversationTurnDTO.CitationDTO> citationList = new ArrayList<>();
        for (ConversationCitation citation : citations) {
            if (citation == null) {
                continue;
            }
            ConversationTurnDTO.CitationDTO dto = new ConversationTurnDTO.CitationDTO();
            dto.setFileName(citation.getFileName());
            dto.setPageNo(citation.getPageNo());
            dto.setSnippet(citation.getSnippet());
            dto.setHitType(citation.getHitType());
            dto.setAssetId(citation.getAssetId());
            dto.setSegmentId(citation.getSegmentId());
            citationList.add(dto);
        }
        return citationList;
    }

    private record MessagePipelineResult(RewriteResult rewriteResult, ConversationRetrievalResult retrievalResult,
                                         List<ResultCardDTO> resultCards,
                                         List<ConversationCitation> answerCitations,
                                         AnswerGenerationResult answerGenerationResult) {
    }
}
