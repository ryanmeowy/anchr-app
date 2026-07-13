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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extractive search answer service grounded by returned segments.
 */
@Service
@RequiredArgsConstructor
public class SearchAnswerServiceImpl implements SearchAnswerService {

    private static final int ASSET_CITATION_LIMIT = 3;
    private static final int CHUNK_LIMIT_PER_ASSET = 3;

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
        List<CitationSource> sources = new ArrayList<>();
        for (SearchResultDTO result : results) {
            if (result == null) {
                continue;
            }
            if (result.getTopChunks() == null || result.getTopChunks().isEmpty()) {
                sources.add(new CitationSource(result, null));
                continue;
            }
            result.getTopChunks().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(topChunk -> new CitationSource(result, topChunk))
                    .forEach(sources::add);
        }
        sources.sort(Comparator.comparing(
                source -> source.score() == null ? 0.0D : source.score(),
                Comparator.reverseOrder()));

        Map<String, SearchAnswerDTO.CitationDTO> groups = new LinkedHashMap<>();
        for (CitationSource source : sources) {
            if (!StringUtils.hasText(source.segmentId()) || !StringUtils.hasText(source.snippet())) {
                continue;
            }
            String assetKey = StringUtils.hasText(source.result().getAssetId())
                    ? source.result().getAssetId()
                    : "segment:" + source.segmentId();
            SearchAnswerDTO.CitationDTO group = groups.get(assetKey);
            if (group == null) {
                if (groups.size() >= ASSET_CITATION_LIMIT) {
                    continue;
                }
                group = SearchAnswerDTO.CitationDTO.builder()
                        .citationIndex(groups.size() + 1)
                        .assetId(source.result().getAssetId())
                        .kbId(source.result().getKbId())
                        .fileName(resolveFileName(source.sourceRef()))
                        .chunks(new ArrayList<>())
                        .build();
                groups.put(assetKey, group);
            }
            if (group.getChunks().size() < CHUNK_LIMIT_PER_ASSET
                    && group.getChunks().stream().noneMatch(chunk -> source.segmentId().equals(chunk.getSegmentId()))) {
                group.getChunks().add(SearchAnswerDTO.CitationChunkDTO.builder()
                        .segmentId(source.segmentId())
                        .pageNo(source.pageNo())
                        .chunkOrder(source.chunkOrder())
                        .title(source.title())
                        .content(source.content())
                        .snippet(source.snippet())
                        .anchor(source.anchor())
                        .why(buildCitationWhy(source.result(), source.score()))
                        .build());
            }
        }
        Comparator<SearchAnswerDTO.CitationChunkDTO> documentOrder = Comparator
                .comparing(SearchAnswerDTO.CitationChunkDTO::getPageNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SearchAnswerDTO.CitationChunkDTO::getChunkOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SearchAnswerDTO.CitationChunkDTO::getSegmentId, Comparator.nullsLast(String::compareTo));
        groups.values().forEach(group -> group.getChunks().sort(documentOrder));
        return new ArrayList<>(groups.values());
    }

    private String buildAnswer(List<SearchAnswerDTO.CitationDTO> citations) {
        StringBuilder builder = new StringBuilder("根据当前检索结果，可以参考以下证据：");
        for (SearchAnswerDTO.CitationDTO citation : citations) {
            String evidence = citation.getChunks().stream()
                    .map(SearchAnswerDTO.CitationChunkDTO::getSnippet)
                    .filter(StringUtils::hasText)
                    .limit(CHUNK_LIMIT_PER_ASSET)
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
            builder.append(System.lineSeparator())
                    .append("[")
                    .append(citation.getCitationIndex())
                    .append("] ")
                    .append(evidence);
        }
        return builder.toString();
    }

    private String resolveAnswerMode(SearchQueryDTO query) {
        return query == null || !StringUtils.hasText(query.getAnswerMode()) ? "STRICT" : query.getAnswerMode().trim();
    }

    private SearchAnswerDTO.CitationWhy buildCitationWhy(SearchResultDTO result, Double score) {
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

    private record CitationSource(SearchResultDTO result, SearchResultDTO.TopChunk topChunk) {

        private String segmentId() {
            return topChunk == null ? result.getSegmentId() : topChunk.getSegmentId();
        }

        private String snippet() {
            return topChunk == null ? result.getSnippet() : topChunk.getSnippet();
        }

        private String content() {
            if (topChunk != null && StringUtils.hasText(topChunk.getContent())) {
                return topChunk.getContent();
            }
            return result.getContent();
        }

        private String title() {
            return topChunk == null ? result.getTitle() : topChunk.getTitle();
        }

        private Double score() {
            return topChunk == null ? result.getScore() : topChunk.getScore();
        }

        private Integer pageNo() {
            return topChunk == null ? result.getPageNo() : topChunk.getPageNo();
        }

        private Integer chunkOrder() {
            SearchResultDTO.Anchor anchor = topChunk == null ? result.getAnchor() : topChunk.getAnchor();
            return anchor == null ? null : anchor.getChunkOrder();
        }

        private SearchResultDTO.Anchor anchor() {
            return topChunk == null ? result.getAnchor() : topChunk.getAnchor();
        }

        private String sourceRef() {
            if (topChunk != null && StringUtils.hasText(topChunk.getSourceRef())) {
                return topChunk.getSourceRef();
            }
            return result.getSourceRef();
        }
    }
}
