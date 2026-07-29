package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.CitationReasonGenerationService;
import com.anchr.core.search.application.SearchAnswerService;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.application.api.model.SearchAnswerRequest;
import com.anchr.core.search.application.api.model.SearchAnswerResult;
import com.anchr.core.search.domain.model.SegmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extractive search answer service grounded by already returned retrieval hits. */
@Service
@RequiredArgsConstructor
public class SearchAnswerServiceImpl implements SearchAnswerService {

    private static final int ASSET_CITATION_LIMIT = 3;
    private static final int CHUNK_LIMIT_PER_ASSET = 3;

    private final CitationReasonGenerationService citationReasonGenerationService;

    @Override
    public SearchAnswerResult answer(SearchAnswerRequest request, List<RetrievalHit> existingResults) {
        List<RetrievalHit> results = existingResults == null ? List.of() : List.copyOf(existingResults);
        List<SearchAnswerResult.Citation> citations = buildCitations(results);
        if (citations.isEmpty()) {
            return new SearchAnswerResult(
                    null, List.of(), results,
                    new SearchAnswerResult.AnswerTrace(resolveAnswerMode(request), false, "NO_ENOUGH_EVIDENCE"));
        }
        String answer = buildAnswer(citations);
        citations = enrichCitationReasons(request, answer, citations);
        return new SearchAnswerResult(
                answer, citations, results,
                new SearchAnswerResult.AnswerTrace(resolveAnswerMode(request), true, null));
    }

    private List<SearchAnswerResult.Citation> enrichCitationReasons(
            SearchAnswerRequest request,
            String answer,
            List<SearchAnswerResult.Citation> citations) {
        CitationReasonGenerationService.Request generationRequest = new CitationReasonGenerationService.Request(
                request == null ? null : request.question(),
                null,
                answer,
                citations.stream().map(citation -> new CitationReasonGenerationService.CitationGroup(
                        citation.citationIndex(),
                        citation.assetId(),
                        citation.chunks().stream().map(chunk -> new CitationReasonGenerationService.CitationChunk(
                                chunk.segmentId(), chunk.content(),
                                chunk.why() == null ? null : chunk.why().score(),
                                chunk.why() == null ? List.of() : chunk.why().hitSources(),
                                chunk.why() == null ? null : chunk.why().matchSummary()
                        )).toList()
                )).toList());
        Map<String, String> reasons = citationReasonGenerationService.generate(generationRequest);
        return citations.stream().map(citation -> new SearchAnswerResult.Citation(
                citation.citationIndex(), citation.assetId(), citation.kbId(), citation.fileName(),
                citation.chunks().stream().map(chunk -> {
                    String reason = StringUtils.hasText(chunk.segmentId()) ? reasons.get(chunk.segmentId()) : null;
                    SearchAnswerResult.CitationWhy why = chunk.why();
                    if (why != null && StringUtils.hasText(reason)) why = why.withReason(reason);
                    return new SearchAnswerResult.CitationChunk(
                            chunk.segmentId(), chunk.pageNo(), chunk.chunkOrder(), chunk.title(),
                            chunk.content(), chunk.snippet(), chunk.anchor(), why);
                }).toList())).toList();
    }

    private List<SearchAnswerResult.Citation> buildCitations(List<RetrievalHit> results) {
        if (results.isEmpty()) return List.of();
        List<CitationSource> sources = new ArrayList<>();
        for (RetrievalHit result : results) {
            if (result == null) continue;
            if (result.topChunks() == null || result.topChunks().isEmpty()) {
                sources.add(new CitationSource(result, null));
            } else {
                result.topChunks().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(topChunk -> new CitationSource(result, topChunk))
                        .forEach(sources::add);
            }
        }
        sources.sort(Comparator.comparing(
                source -> source.score() == null ? 0.0D : source.score(),
                Comparator.reverseOrder()));

        Map<String, CitationGroup> groups = new LinkedHashMap<>();
        for (CitationSource source : sources) {
            if (SegmentType.isImageVisual(source.segmentType())
                    || !StringUtils.hasText(source.segmentId())
                    || !StringUtils.hasText(source.snippet())) continue;
            String assetKey = StringUtils.hasText(source.result().assetId())
                    ? source.result().assetId() : "segment:" + source.segmentId();
            CitationGroup group = groups.get(assetKey);
            if (group == null) {
                if (groups.size() >= ASSET_CITATION_LIMIT) continue;
                group = new CitationGroup(
                        groups.size() + 1, source.result().assetId(), source.result().kbId(),
                        resolveFileName(source), new ArrayList<>());
                groups.put(assetKey, group);
            }
            if (group.chunks().size() < CHUNK_LIMIT_PER_ASSET
                    && group.chunks().stream().noneMatch(chunk -> source.segmentId().equals(chunk.segmentId()))) {
                group.chunks().add(new SearchAnswerResult.CitationChunk(
                        source.segmentId(), source.pageNo(), source.chunkOrder(), source.title(),
                        source.content(), source.snippet(), source.anchor(),
                        buildCitationWhy(source.explain(), source.score())));
            }
        }
        Comparator<SearchAnswerResult.CitationChunk> documentOrder = Comparator
                .comparing(SearchAnswerResult.CitationChunk::pageNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SearchAnswerResult.CitationChunk::chunkOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SearchAnswerResult.CitationChunk::segmentId, Comparator.nullsLast(String::compareTo));
        return groups.values().stream().map(group -> {
            group.chunks().sort(documentOrder);
            return new SearchAnswerResult.Citation(
                    group.citationIndex(), group.assetId(), group.kbId(), group.fileName(), group.chunks());
        }).toList();
    }

    private String buildAnswer(List<SearchAnswerResult.Citation> citations) {
        StringBuilder builder = new StringBuilder("根据当前检索结果，可以参考以下证据：");
        for (SearchAnswerResult.Citation citation : citations) {
            String evidence = citation.chunks().stream()
                    .map(SearchAnswerResult.CitationChunk::snippet)
                    .filter(StringUtils::hasText)
                    .limit(CHUNK_LIMIT_PER_ASSET)
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
            builder.append(System.lineSeparator()).append("[")
                    .append(citation.citationIndex()).append("] ").append(evidence);
        }
        return builder.toString();
    }

    private String resolveAnswerMode(SearchAnswerRequest request) {
        return request == null || !StringUtils.hasText(request.answerMode())
                ? "STRICT" : request.answerMode().trim();
    }

    private SearchAnswerResult.CitationWhy buildCitationWhy(RetrievalExplain explain, Double score) {
        List<String> hitSources = explain == null ? List.of() : explain.hitSources();
        SearchAnswerResult.CitationWhy.MatchedBy matchedBy = null;
        if (explain != null && explain.matchedBy() != null) {
            RetrievalExplain.MatchedBy source = explain.matchedBy();
            matchedBy = new SearchAnswerResult.CitationWhy.MatchedBy(
                    source.vector(), source.title(), source.content(), source.ocr());
        }
        return new SearchAnswerResult.CitationWhy(
                score, hitSources, matchedBy,
                SearchAnswerResult.CitationWhy.buildSummary(score, hitSources, matchedBy), null);
    }

    private String resolveFileName(CitationSource source) {
        if (SegmentType.DOCUMENT_IMAGE.name().equals(source.segmentType())
                && StringUtils.hasText(source.title())) return source.title().trim();
        String sourceRef = source.sourceRef();
        if (!StringUtils.hasText(sourceRef)) return null;
        String trimmed = sourceRef.trim();
        int queryIndex = trimmed.indexOf('?');
        String path = queryIndex >= 0 ? trimmed.substring(0, queryIndex) : trimmed;
        int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slashIndex < 0 || slashIndex == path.length() - 1 ? path : path.substring(slashIndex + 1);
    }

    private record CitationGroup(
            Integer citationIndex, String assetId, String kbId, String fileName,
            List<SearchAnswerResult.CitationChunk> chunks) {
    }

    private record CitationSource(RetrievalHit result, RetrievalTopChunk topChunk) {
        private String segmentId() { return topChunk == null ? result.segmentId() : topChunk.segmentId(); }
        private String segmentType() { return topChunk == null ? result.segmentType() : topChunk.segmentType(); }
        private String snippet() { return topChunk == null ? result.snippet() : topChunk.snippet(); }
        private String content() {
            return topChunk != null && StringUtils.hasText(topChunk.content()) ? topChunk.content() : result.content();
        }
        private String title() { return topChunk == null ? result.title() : topChunk.title(); }
        private Double score() { return topChunk == null ? result.score() : topChunk.score(); }
        private RetrievalExplain explain() { return topChunk == null ? result.explain() : topChunk.explain(); }
        private Integer pageNo() { return topChunk == null ? result.pageNo() : topChunk.pageNo(); }
        private Integer chunkOrder() {
            var anchor = topChunk == null ? result.anchor() : topChunk.anchor();
            return anchor == null ? null : anchor.chunkOrder();
        }
        private com.anchr.core.search.application.api.model.RetrievalAnchor anchor() {
            return topChunk == null ? result.anchor() : topChunk.anchor();
        }
        private String sourceRef() {
            return topChunk != null && StringUtils.hasText(topChunk.sourceRef())
                    ? topChunk.sourceRef() : result.sourceRef();
        }
    }
}
