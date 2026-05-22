package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.KbSearchAnswerService;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.interfaces.rest.dto.KbAnswerDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Extractive search answer service grounded by returned segments.
 */
@Service
@RequiredArgsConstructor
public class KbSearchAnswerServiceImpl implements KbSearchAnswerService {

    private static final int CITATION_LIMIT = 5;

    private final UnifiedSearchService unifiedSearchService;

    @Override
    public KbAnswerDTO answer(KbSearchQueryDTO query) {
        List<KbSearchResultDTO> results = unifiedSearchService.search(query);
        List<KbAnswerDTO.CitationDTO> citations = buildCitations(results);
        if (citations.isEmpty()) {
            return KbAnswerDTO.builder()
                    .answer(null)
                    .citations(List.of())
                    .results(results)
                    .answerTrace(KbAnswerDTO.AnswerTraceDTO.builder()
                            .mode(resolveAnswerMode(query))
                            .grounded(false)
                            .fallbackReason("NO_ENOUGH_EVIDENCE")
                            .build())
                    .build();
        }
        return KbAnswerDTO.builder()
                .answer(buildAnswer(citations))
                .citations(citations)
                .results(results)
                .answerTrace(KbAnswerDTO.AnswerTraceDTO.builder()
                        .mode(resolveAnswerMode(query))
                        .grounded(true)
                        .build())
                .build();
    }

    private List<KbAnswerDTO.CitationDTO> buildCitations(List<KbSearchResultDTO> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<KbAnswerDTO.CitationDTO> citations = new ArrayList<>();
        for (KbSearchResultDTO result : results) {
            if (result == null || !StringUtils.hasText(result.getSegmentId()) || !StringUtils.hasText(result.getSnippet())) {
                continue;
            }
            citations.add(KbAnswerDTO.CitationDTO.builder()
                    .citationIndex(citations.size() + 1)
                    .segmentId(result.getSegmentId())
                    .assetId(result.getAssetId())
                    .kbId(result.getKbId())
                    .fileName(resolveFileName(result.getSourceRef()))
                    .pageNo(result.getPageNo())
                    .snippet(result.getSnippet())
                    .build());
            if (citations.size() >= CITATION_LIMIT) {
                break;
            }
        }
        return citations;
    }

    private String buildAnswer(List<KbAnswerDTO.CitationDTO> citations) {
        StringBuilder builder = new StringBuilder("根据当前检索结果，可以参考以下证据：");
        for (KbAnswerDTO.CitationDTO citation : citations) {
            builder.append(System.lineSeparator())
                    .append("[")
                    .append(citation.getCitationIndex())
                    .append("] ")
                    .append(citation.getSnippet());
        }
        return builder.toString();
    }

    private String resolveAnswerMode(KbSearchQueryDTO query) {
        return query == null || !StringUtils.hasText(query.getAnswerMode()) ? "STRICT" : query.getAnswerMode().trim();
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
}
