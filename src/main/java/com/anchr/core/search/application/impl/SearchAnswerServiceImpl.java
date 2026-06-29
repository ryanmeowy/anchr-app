package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.SearchAnswerService;
import com.anchr.core.search.application.UnifiedSearchService;
import com.anchr.core.search.interfaces.rest.dto.SearchAnswerDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
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
public class SearchAnswerServiceImpl implements SearchAnswerService {

    private static final int CITATION_LIMIT = 5;

    private final UnifiedSearchService unifiedSearchService;

    @Override
    public SearchAnswerDTO answer(SearchQueryDTO query) {
        return answer(query, unifiedSearchService.search(query));
    }

    @Override
    public SearchAnswerDTO answer(SearchQueryDTO query, List<SearchResultDTO> existingResults) {
        List<SearchResultDTO> results = existingResults != null ? existingResults : List.of();
        List<SearchAnswerDTO.CitationDTO> citations = buildCitations(results);
        if (citations.isEmpty()) {
            return SearchAnswerDTO.builder()
                    .answer(null)
                    .citations(List.of())
                    .results(results)
                    .answerTrace(SearchAnswerDTO.AnswerTraceDTO.builder()
                            .mode(resolveAnswerMode(query))
                            .grounded(false)
                            .fallbackReason("NO_ENOUGH_EVIDENCE")
                            .build())
                    .build();
        }
        return SearchAnswerDTO.builder()
                .answer(buildAnswer(citations))
                .citations(citations)
                .results(results)
                .answerTrace(SearchAnswerDTO.AnswerTraceDTO.builder()
                        .mode(resolveAnswerMode(query))
                        .grounded(true)
                        .build())
                .build();
    }

    private List<SearchAnswerDTO.CitationDTO> buildCitations(List<SearchResultDTO> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<SearchAnswerDTO.CitationDTO> citations = new ArrayList<>();
        for (SearchResultDTO result : results) {
            if (result == null || !StringUtils.hasText(result.getSegmentId()) || !StringUtils.hasText(result.getSnippet())) {
                continue;
            }
            citations.add(SearchAnswerDTO.CitationDTO.builder()
                    .citationIndex(citations.size() + 1)
                    .segmentId(result.getSegmentId())
                    .assetId(result.getAssetId())
                    .kbId(result.getKbId())
                    .fileName(resolveFileName(result.getSourceRef()))
                    .pageNo(result.getPageNo())
                    .snippet(result.getSnippet())
                    .why(buildCitationWhy(result))
                    .build());
            if (citations.size() >= CITATION_LIMIT) {
                break;
            }
        }
        return citations;
    }

    private String buildAnswer(List<SearchAnswerDTO.CitationDTO> citations) {
        StringBuilder builder = new StringBuilder("根据当前检索结果，可以参考以下证据：");
        for (SearchAnswerDTO.CitationDTO citation : citations) {
            builder.append(System.lineSeparator())
                    .append("[")
                    .append(citation.getCitationIndex())
                    .append("] ")
                    .append(citation.getSnippet());
        }
        return builder.toString();
    }

    private String resolveAnswerMode(SearchQueryDTO query) {
        return query == null || !StringUtils.hasText(query.getAnswerMode()) ? "STRICT" : query.getAnswerMode().trim();
    }

    private SearchAnswerDTO.CitationWhy buildCitationWhy(SearchResultDTO result) {
        Double score = result.getScore();
        SearchExplainDTO explain = result.getExplain();
        List<String> hitSources = explain != null && explain.getHitSources() != null
                ? List.copyOf(explain.getHitSources()) : List.of();
        SearchAnswerDTO.CitationWhy.MatchedBy matchedBy = null;
        if (explain != null && explain.getMatchedBy() != null) {
            SearchExplainDTO.MatchedBy mb = explain.getMatchedBy();
            matchedBy = SearchAnswerDTO.CitationWhy.MatchedBy.builder()
                    .vector(mb.isVector())
                    .title(mb.isTitle())
                    .content(mb.isContent())
                    .ocr(mb.isOcr())
                    .build();
        }
        String matchSummary = SearchAnswerDTO.CitationWhy.buildSummary(score, hitSources, matchedBy);
        return SearchAnswerDTO.CitationWhy.builder()
                .score(score)
                .hitSources(hitSources)
                .matchedBy(matchedBy)
                .matchSummary(matchSummary)
                .build();
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
