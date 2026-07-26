package com.anchr.core.conversation.application.assembler;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ResultCardDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ResultHitDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds asset-level result cards from the current retrieval candidates.
 */
@Component
public class ConversationResultCardMapper {

    private static final int MAX_RESULT_CARDS = 3;
    private static final int MAX_ADDITIONAL_HITS = 2;

    public List<ResultCardDTO> map(List<ConversationRetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ConversationRetrievalCandidate> sortedCandidates = candidates.stream()
                .filter(this::canBuildHit)
                .sorted(candidateComparator())
                .toList();
        if (sortedCandidates.isEmpty()) {
            return List.of();
        }

        Map<String, List<ConversationRetrievalCandidate>> candidatesByAsset = new LinkedHashMap<>();
        for (ConversationRetrievalCandidate candidate : sortedCandidates) {
            candidatesByAsset.computeIfAbsent(candidate.getAssetId().trim(), ignored -> new ArrayList<>())
                    .add(candidate);
        }

        List<ResultCardDTO> cards = new ArrayList<>();
        for (List<ConversationRetrievalCandidate> assetCandidates : candidatesByAsset.values()) {
            ResultCardDTO card = toCard(assetCandidates);
            if (card != null) {
                cards.add(card);
            }
            if (cards.size() >= MAX_RESULT_CARDS) {
                break;
            }
        }
        return cards;
    }

    private ResultCardDTO toCard(List<ConversationRetrievalCandidate> assetCandidates) {
        if (assetCandidates == null || assetCandidates.isEmpty()) {
            return null;
        }
        ConversationRetrievalCandidate primaryCandidate = assetCandidates.getFirst();
        ResultHitDTO primaryHit = toHit(primaryCandidate);
        if (primaryHit == null) {
            return null;
        }

        ResultCardDTO card = new ResultCardDTO();
        card.setAssetId(primaryCandidate.getAssetId());
        card.setAssetType(resolveAssetType(primaryCandidate));
        card.setFileName(resolveFileName(primaryCandidate));
        card.setTitle(resolveTitle(primaryCandidate));
        card.setScore(primaryHit.getScore());
        card.setHitCount(assetCandidates.size());
        card.setPrimaryHit(primaryHit);
        card.setAdditionalHits(assetCandidates.stream()
                .skip(1)
                .limit(MAX_ADDITIONAL_HITS)
                .map(this::toHit)
                .filter(Objects::nonNull)
                .toList());
        return card;
    }

    private ResultHitDTO toHit(ConversationRetrievalCandidate candidate) {
        if (!canBuildHit(candidate)) {
            return null;
        }
        ResultHitDTO hit = new ResultHitDTO();
        hit.setSegmentId(candidate.getSegmentId().trim());
        hit.setSnippet(candidate.getSnippet());
        hit.setScore(candidate.getScore());
        hit.setPageNo(candidate.getPageNo());
        hit.setAnchor(toAnchor(candidate));
        hit.setHitType(candidate.getSegmentType());
        return hit;
    }

    private PreviewAnchorDTO toAnchor(ConversationRetrievalCandidate candidate) {
        ConversationRetrievalCandidate.Anchor source = candidate.getAnchor();
        if (source == null && candidate.getPageNo() == null) {
            return null;
        }
        if (source == null) {
            return PreviewAnchorDTO.builder()
                    .pageNo(candidate.getPageNo())
                    .build();
        }
        return PreviewAnchorDTO.builder()
                .pageNo(source.getPageNo() == null ? candidate.getPageNo() : source.getPageNo())
                .chunkOrder(source.getChunkOrder())
                .bbox(source.getBbox())
                .imageWidth(source.getImageWidth())
                .imageHeight(source.getImageHeight())
                .build();
    }

    private Comparator<ConversationRetrievalCandidate> candidateComparator() {
        return Comparator
                .comparing((ConversationRetrievalCandidate candidate) -> nullSafeScore(candidate.getScore())).reversed()
                .thenComparing(candidate -> nullSafeText(candidate.getAssetId()))
                .thenComparing(candidate -> nullSafeText(candidate.getSegmentId()));
    }

    private boolean canBuildHit(ConversationRetrievalCandidate candidate) {
        return candidate != null
                && StringUtils.hasText(candidate.getAssetId())
                && StringUtils.hasText(candidate.getSegmentId());
    }

    private double nullSafeScore(Double score) {
        return score == null ? 0.0d : score;
    }

    private String resolveAssetType(ConversationRetrievalCandidate candidate) {
        if (StringUtils.hasText(candidate.getAssetType())) {
            return candidate.getAssetType().trim();
        }
        if (StringUtils.hasText(candidate.getResultType())) {
            return candidate.getResultType().trim();
        }
        return null;
    }

    private String resolveFileName(ConversationRetrievalCandidate candidate) {
        if ("DOCUMENT_IMAGE".equalsIgnoreCase(candidate.getSegmentType())
                && StringUtils.hasText(candidate.getTitle())) {
            return candidate.getTitle().trim();
        }
        if (!StringUtils.hasText(candidate.getSourceRef())) {
            return null;
        }
        String sourceRef = candidate.getSourceRef().trim();
        int slashIndex = sourceRef.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex == sourceRef.length() - 1) {
            return sourceRef;
        }
        return sourceRef.substring(slashIndex + 1);
    }

    private String resolveTitle(ConversationRetrievalCandidate candidate) {
        String fileName = resolveFileName(candidate);
        if (StringUtils.hasText(fileName)) {
            return fileName;
        }
        return candidate.getAssetId();
    }

    private String nullSafeText(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }
}
