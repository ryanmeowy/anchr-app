package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.AnswerGenerationService;
import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.QueryRewriteService;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationResultCardMapper;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationMessagePipeline {

    private static final int ANSWER_CITATION_LIMIT = 5;

    private final QueryRewriteService queryRewriteService;
    private final ConversationRetrievalOrchestrator conversationRetrievalOrchestrator;
    private final ConversationCitationMapper conversationCitationMapper;
    private final ConversationResultCardMapper conversationResultCardMapper;
    private final AnswerGenerationService answerGenerationService;

    public ConversationMessagePipelineResult execute(String sessionId, ConversationMessageRequestDTO request) {
        RewriteResult rewriteResult = queryRewriteService.rewrite(sessionId, request.getQuery().trim());
        ConversationRetrievalResult retrievalResult = conversationRetrievalOrchestrator.retrieve(
                rewriteResult.getRewrittenQuery(),
                request.getTopK(),
                request.getLimit(),
                request.getStrategy(),
                request.getKbIds(),
                rewriteResult.getPreferredModalities()
        );
        List<ResultCardDTO> resultCards = conversationResultCardMapper.map(retrievalResult.getTopCandidates());
        LinkedHashSet<String> resultCardSegmentIds = collectResultCardSegmentIds(resultCards);
        List<ConversationRetrievalCandidate> answerCandidates = retrievalResult.getTopCandidates()
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
        return new ConversationMessagePipelineResult(
                rewriteResult,
                retrievalResult,
                resultCards,
                answerCitations,
                answerGenerationResult
        );
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
