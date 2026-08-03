package com.anchr.core.conversation.application;

import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.model.ConversationCitationReasonRequest;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

import static com.anchr.core.common.constant.CitationConstant.REASON_MAX_LENGTH;

/** Adds presentation reasons after the final answer and citation set have been verified. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationCitationReasonEnricher {

    private final ConversationTurnCodec conversationTurnCodec;
    private final ConversationRetrievalAcl conversationRetrievalAcl;

    public void enrich(String question,
                       String rewrittenQuery,
                       String answer,
                       List<ConversationCitation> citations) {
        if (citations == null || citations.isEmpty()) return;

        Map<String, String> reasons;
        try {
            reasons = conversationRetrievalAcl.generateCitationReasons(
                    toRequest(question, rewrittenQuery, answer, citations));
            if (reasons == null) reasons = Map.of();
        } catch (RuntimeException exception) {
            log.warn("Citation reason enrichment failed, citationCount={}, errorType={}",
                    citations.size(), exception.getClass().getSimpleName());
            reasons = Map.of();
        }

        for (ConversationCitation citation : citations) {
            if (citation == null || citation.getWhy() == null
                    || !StringUtils.hasText(citation.getSegmentId())) {
                continue;
            }
            String reason = reasons.get(citation.getSegmentId());
            if (!StringUtils.hasText(reason)) {
                reason = citation.getWhy().getMatchSummary();
            }
            if (StringUtils.hasText(reason)) {
                String normalized = reason.trim();
                citation.getWhy().setReason(normalized.length() <= REASON_MAX_LENGTH
                        ? normalized : normalized.substring(0, REASON_MAX_LENGTH));
            }
        }
    }

    private ConversationCitationReasonRequest toRequest(
            String question,
            String rewrittenQuery,
            String answer,
            List<ConversationCitation> citations
    ) {
        List<ConversationTurnDTO.CitationDTO> groups =
                conversationTurnCodec.toCitationDTOs(citations);
        return new ConversationCitationReasonRequest(
                question,
                rewrittenQuery,
                answer,
                groups.stream().map(group -> new ConversationCitationReasonRequest.CitationGroup(
                        group.getCitationIndex(),
                        group.getAssetId(),
                        group.getChunks().stream().map(chunk ->
                                new ConversationCitationReasonRequest.CitationChunk(
                                        chunk.getSegmentId(),
                                        chunk.getContent(),
                                        chunk.getWhy() == null ? null : chunk.getWhy().getScore(),
                                        chunk.getWhy() == null ? List.of() : chunk.getWhy().getHitSources(),
                                        chunk.getWhy() == null ? null : chunk.getWhy().getMatchSummary()
                                )).toList()
                )).toList()
        );
    }
}
