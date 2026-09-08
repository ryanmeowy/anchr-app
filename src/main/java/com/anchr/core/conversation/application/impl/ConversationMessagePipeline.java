package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.AnswerGenerationService;
import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationResultCardMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationMessagePipeline {

    private static final int ANSWER_CITATION_LIMIT = 5;

    private final TraditionalRagRewriteService queryRewriteService;
    private final ConversationRetrievalOrchestrator conversationRetrievalOrchestrator;
    private final ConversationCitationMapper conversationCitationMapper;
    private final ConversationResultCardMapper conversationResultCardMapper;
    private final AnswerGenerationService answerGenerationService;

    public ConversationMessagePipelineResult execute(String sessionId, ConversationMessageRequestDTO request) {
        return execute(sessionId, request, ConversationProgressListener.NOOP);
    }

    public ConversationMessagePipelineResult execute(String sessionId,
                                                     ConversationMessageRequestDTO request,
                                                     ConversationProgressListener progress) {
        RewriteResult rewriteResult = queryRewriteService.rewrite(sessionId, request.getQuery().trim());
        log.info("Traditional retrieval started, sessionId={}, originalQuery={}, resolvedQuestion={}, query={}, keywords={}, "
                        + "rewriteFallback={}, rewriteReason={}, kbIds={}, assetIds={}, modalities={}, limit={}",
                sessionId, logQuery(request.getQuery()), logQuery(rewriteResult.getResolvedQuestion()), logQuery(rewriteResult.getRewrittenQuery()), rewriteResult.getKeywords(),
                rewriteResult.isFallbackUsed(), logQuery(rewriteResult.getRewriteReason()),
                request.getKbIds(), request.getAssetIdList(), request.getPreferredModalities(), request.getLimit());
        ConversationMessagePipelineResult result = execute(request, rewriteResult, progress);
        log.info("Traditional retrieval completed, sessionId={}, query={}, keywords={}, segmentIds={}, "
                        + "answerStatus={}, fallbackReason={}",
                sessionId, logQuery(rewriteResult.getRewrittenQuery()), rewriteResult.getKeywords(),
                result.retrievalResult().getTopCandidates().stream()
                        .map(ConversationRetrievalCandidate::getSegmentId).toList(),
                AnswerStatus.from(result.answerGenerationResult()),
                result.answerGenerationResult().getFallbackReason());
        return result;
    }

    private static String logQuery(String value) {
        return value == null ? null : value.replace('\r', ' ').replace('\n', ' ');
    }

    public ConversationMessagePipelineResult execute(ConversationMessageRequestDTO request,
                                                     RewriteResult rewriteResult) {
        return execute(request, rewriteResult, ConversationProgressListener.NOOP);
    }

    public ConversationMessagePipelineResult execute(ConversationMessageRequestDTO request,
                                                     RewriteResult rewriteResult,
                                                     ConversationProgressListener progress) {
        ConversationRetrievalResult retrievalResult = rewriteResult.getKeywords() != null && !rewriteResult.getKeywords().isEmpty()
                ? conversationRetrievalOrchestrator.retrieve(rewriteResult.getRewrittenQuery(), rewriteResult.getKeywords(),
                        request.getLimit(), request.getKbIds(), request.getPreferredModalities(), request.getAssetIdList())
                : conversationRetrievalOrchestrator.retrieve(
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
                .filter(ConversationRetrievalCandidate::isCitableEvidence)
                .limit(ANSWER_CITATION_LIMIT)
                .toList();
        List<ConversationCitation> candidateCitations = conversationCitationMapper.mapFromSearchResults(answerCandidates);
        AnswerGenerationResult answerGenerationResult = progress != null && progress.supportsAnswerStreaming()
                ? answerGenerationService.generateStream(
                        request.getQuery().trim(),
                        resolveQuestion(request, rewriteResult),
                        AnswerMode.from(request.getAnswerMode()),
                        answerCandidates,
                        candidateCitations,
                        progress)
                : answerGenerationService.generate(
                        request.getQuery().trim(),
                        resolveQuestion(request, rewriteResult),
                        AnswerMode.from(request.getAnswerMode()),
                        answerCandidates,
                        candidateCitations);
        List<ConversationCitation> answerCitations = filterEffectiveCitations(
                candidateCitations,
                answerGenerationResult.getAnswerInputSegmentIds(),
                AnswerStatus.from(answerGenerationResult)
        );
        return new ConversationMessagePipelineResult(
                rewriteResult,
                retrievalResult,
                resultCards,
                answerCitations,
                answerGenerationResult
        );
    }

    private String resolveQuestion(ConversationMessageRequestDTO request, RewriteResult rewrite) {
        return StringUtils.hasText(rewrite.getResolvedQuestion())
                ? rewrite.getResolvedQuestion() : request.getQuery().trim();
    }

    private List<ConversationCitation> filterEffectiveCitations(List<ConversationCitation> candidateCitations,
                                                                 List<String> answerInputSegmentIds,
                                                                 AnswerStatus answerStatus) {
        if (answerStatus == AnswerStatus.NO_EVIDENCE
                || answerStatus == AnswerStatus.GENERATION_FAILED
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
