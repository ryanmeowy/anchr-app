package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.AnswerGenerationService;
import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.QueryRewriteService;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationResultCardMapper;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.AnswerMode;
import com.anchr.core.conversation.application.model.AnswerStatus;
import com.anchr.core.conversation.application.model.AnswerGenerationResult;
import com.anchr.core.conversation.application.model.ConversationMessagePipelineResult;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.conversation.application.model.RewriteResult;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ResultCardDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ResultHitDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.search.application.CitationReasonGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConversationMessagePipeline {

    private static final int ANSWER_CITATION_LIMIT = 5;

    private final QueryRewriteService queryRewriteService;
    private final ConversationRetrievalOrchestrator conversationRetrievalOrchestrator;
    private final ConversationCitationMapper conversationCitationMapper;
    private final ConversationResultCardMapper conversationResultCardMapper;
    private final AnswerGenerationService answerGenerationService;
    private final ConversationTurnCodec conversationTurnCodec;
    private final CitationReasonGenerationService citationReasonGenerationService;

    public ConversationMessagePipelineResult execute(String sessionId, ConversationMessageRequestDTO request) {
        RewriteResult rewriteResult = queryRewriteService.rewrite(sessionId, request.getQuery().trim());
        return execute(request, rewriteResult);
    }

    public ConversationMessagePipelineResult execute(ConversationMessageRequestDTO request,
                                                     RewriteResult rewriteResult) {
        ConversationRetrievalResult retrievalResult = conversationRetrievalOrchestrator.retrieve(
                rewriteResult.getRewrittenQuery(),
                request.getLimit(),
                request.getKbIds(),
                request.getPreferredModalities(),
                request.getAssetIdList()
        );
        List<ResultCardDTO> resultCards = conversationResultCardMapper.map(retrievalResult.getTopCandidates());
        LinkedHashSet<String> resultCardSegmentIds = collectResultCardSegmentIds(resultCards);
        List<ConversationRetrievalCandidate> answerCandidates = retrievalResult.getTopCandidates()
                .stream()
                .filter(candidate -> isTraceableCandidate(candidate, resultCardSegmentIds))
                .limit(ANSWER_CITATION_LIMIT)
                .toList();
        List<ConversationCitation> candidateCitations = conversationCitationMapper.mapFromSearchResults(answerCandidates);
        AnswerGenerationResult answerGenerationResult = answerGenerationService.generate(
                request.getQuery().trim(),
                rewriteResult.getRewrittenQuery(),
                AnswerMode.from(request.getAnswerMode()),
                answerCandidates,
                candidateCitations
        );
        List<ConversationCitation> answerCitations = filterEffectiveCitations(
                candidateCitations,
                answerGenerationResult.getAnswerInputSegmentIds(),
                AnswerStatus.from(answerGenerationResult)
        );
        enrichCitationReasons(
                request.getQuery().trim(),
                rewriteResult.getRewrittenQuery(),
                answerGenerationResult.getAnswerText(),
                answerCitations
        );
        return new ConversationMessagePipelineResult(
                rewriteResult,
                retrievalResult,
                resultCards,
                answerCitations,
                answerGenerationResult
        );
    }

    private void enrichCitationReasons(String question,
                                       String rewrittenQuery,
                                       String answer,
                                       List<ConversationCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        List<ConversationTurnDTO.CitationDTO> groups = conversationTurnCodec.toCitationDTOs(citations);
        CitationReasonGenerationService.Request reasonRequest = new CitationReasonGenerationService.Request(
                question,
                rewrittenQuery,
                answer,
                groups.stream().map(group -> new CitationReasonGenerationService.CitationGroup(
                        group.getCitationIndex(),
                        group.getAssetId(),
                        group.getChunks().stream().map(chunk -> new CitationReasonGenerationService.CitationChunk(
                                chunk.getSegmentId(),
                                chunk.getContent(),
                                chunk.getWhy() == null ? null : chunk.getWhy().getScore(),
                                chunk.getWhy() == null ? List.of() : chunk.getWhy().getHitSources(),
                                chunk.getWhy() == null ? null : chunk.getWhy().getMatchSummary()
                        )).toList()
                )).toList()
        );
        Map<String, String> reasons = citationReasonGenerationService.generate(reasonRequest);
        for (ConversationCitation citation : citations) {
            if (citation == null || citation.getWhy() == null || !StringUtils.hasText(citation.getSegmentId())) {
                continue;
            }
            String reason = reasons.get(citation.getSegmentId());
            if (StringUtils.hasText(reason)) {
                citation.getWhy().setReason(reason);
            }
        }
    }

    private List<ConversationCitation> filterEffectiveCitations(List<ConversationCitation> candidateCitations,
                                                                 List<String> answerInputSegmentIds,
                                                                 AnswerStatus answerStatus) {
        if (answerStatus == AnswerStatus.NO_EVIDENCE
                || candidateCitations == null || candidateCitations.isEmpty()
                || answerInputSegmentIds == null || answerInputSegmentIds.isEmpty()) {
            return List.of();
        }
        Map<String, ConversationCitation> citationBySegmentId = new LinkedHashMap<>();
        for (ConversationCitation citation : candidateCitations) {
            if (citation != null && StringUtils.hasText(citation.getSegmentId())) {
                citationBySegmentId.putIfAbsent(citation.getSegmentId().trim(), citation);
            }
        }
        if (citationBySegmentId.isEmpty()) {
            return List.of();
        }
        return answerInputSegmentIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(citationBySegmentId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
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

    private boolean isTraceableCandidate(ConversationRetrievalCandidate candidate, LinkedHashSet<String> resultCardSegmentIds) {
        return candidate != null
                && StringUtils.hasText(candidate.getSegmentId())
                && resultCardSegmentIds.contains(candidate.getSegmentId().trim());
    }
}
