package com.smart.vision.core.conversation.application.assembler;

import com.smart.vision.core.conversation.application.model.ConversationRetrievalCandidate;
import com.smart.vision.core.conversation.domain.model.ConversationCitation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps retrieval results to conversation citations.
 */
@Component
public class ConversationCitationMapper {

    private static final String HIT_TYPE_OCR = "OCR";
    private static final String HIT_TYPE_CAPTION = "CAPTION";
    private static final String HIT_TYPE_VECTOR = "VECTOR";
    private static final String HIT_TYPE_TEXT = "TEXT_CHUNK";
    private static final String SEGMENT_IMAGE_OCR = "IMAGE_OCR_BLOCK";
    private static final String SEGMENT_IMAGE_CAPTION = "IMAGE_CAPTION";
    private static final String SEGMENT_TEXT = "TEXT_CHUNK";

    public List<ConversationCitation> mapFromSearchResults(List<ConversationRetrievalCandidate> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<ConversationCitation> citations = new ArrayList<>();
        for (ConversationRetrievalCandidate result : results) {
            if (result == null) {
                continue;
            }
            ConversationCitation citation = new ConversationCitation();
            citation.setFileName(resolveFileName(result.getSourceRef()));
            citation.setPageNo(result.getPageNo());
            citation.setSnippet(resolveSnippet(result));
            citation.setHitType(resolveHitType(result));
            citation.setAssetId(result.getAssetId());
            citation.setSegmentId(result.getSegmentId());
            citations.add(citation);
        }
        return citations;
    }

    private String resolveSnippet(ConversationRetrievalCandidate result) {
        if (StringUtils.hasText(result.getSnippet())) {
            return result.getSnippet();
        }
        if (result.getTopChunks() == null || result.getTopChunks().isEmpty()) {
            return null;
        }
        ConversationRetrievalCandidate.TopChunk topChunk = result.getTopChunks().getFirst();
        return topChunk == null ? null : topChunk.getSnippet();
    }

    private String resolveHitType(ConversationRetrievalCandidate result) {
        String segmentType = safeUpper(result.getSegmentType());
        if (SEGMENT_IMAGE_OCR.equals(segmentType)) {
            return HIT_TYPE_OCR;
        }
        if (SEGMENT_IMAGE_CAPTION.equals(segmentType)) {
            return HIT_TYPE_CAPTION;
        }
        if (SEGMENT_TEXT.equals(segmentType)) {
            return HIT_TYPE_TEXT;
        }
        ConversationRetrievalCandidate.Explain explain = result.getExplain();
        if (explain != null && explain.getHitSources() != null) {
            for (String hitSource : explain.getHitSources()) {
                if (HIT_TYPE_VECTOR.equals(safeUpper(hitSource))) {
                    return HIT_TYPE_VECTOR;
                }
            }
        }
        return segmentType;
    }

    private String resolveFileName(String sourceRef) {
        if (!StringUtils.hasText(sourceRef)) {
            return null;
        }
        String trimmed = sourceRef.trim();
        int queryIndex = trimmed.indexOf('?');
        String path = queryIndex >= 0 ? trimmed.substring(0, queryIndex) : trimmed;
        int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slashIndex < 0 || slashIndex == path.length() - 1) {
            return path;
        }
        return path.substring(slashIndex + 1);
    }

    private String safeUpper(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
