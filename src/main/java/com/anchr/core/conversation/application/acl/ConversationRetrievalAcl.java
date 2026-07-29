package com.anchr.core.conversation.application.acl;

import com.anchr.core.conversation.application.ConversationRetrievalOrchestrator;
import com.anchr.core.conversation.application.model.ConversationCitationReasonRequest;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.search.application.api.RetrievalDocumentContentQueryApi;
import com.anchr.core.search.application.api.RetrievalHitQueryApi;
import com.anchr.core.search.application.api.RetrievalCitationReasonApi;
import com.anchr.core.search.application.api.model.RetrievalCitationReasonRequest;
import com.anchr.core.search.application.api.model.RetrievalDocumentChunk;
import com.anchr.core.search.application.api.model.RetrievalDocumentContentQuery;
import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalHitQuery;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Default retrieval orchestrator for conversation flow.
 */
@Service
@RequiredArgsConstructor
public class ConversationRetrievalAcl implements ConversationRetrievalOrchestrator {

    private static final String MODALITY_MIXED = "MIXED";

    private final RetrievalHitQueryApi retrievalHitQueryApi;
    private final RetrievalDocumentContentQueryApi retrievalDocumentContentQueryApi;
    private final RetrievalCitationReasonApi retrievalCitationReasonApi;
    private final MeterRegistry meterRegistry;

    @Override
    public ConversationRetrievalResult retrieve(String rewrittenQuery,
                                                Integer limit,
                                                List<String> kbIds,
                                                List<String> preferredModalities,
                                                List<String> assetIdList) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RetrievalHitQuery query = new RetrievalHitQuery(
                    rewrittenQuery, limit, kbIds, assetIdList,
                    resolveSegmentTypes(preferredModalities));

            List<RetrievalHit> rawResults = retrievalHitQueryApi.query(query);
            List<ConversationRetrievalCandidate> candidates = rawResults.stream()
                    .flatMap(item -> toCandidates(item).stream())
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(
                            candidate -> candidate.getScore() == null ? 0.0D : candidate.getScore(),
                            Comparator.reverseOrder()))
                    .toList();
            ConversationRetrievalResult result = new ConversationRetrievalResult();
            result.setTopCandidates(candidates);
            meterRegistry.summary("conversation.retrieval.topk").record(candidates.size());
            if (candidates.isEmpty()) {
                meterRegistry.counter("conversation.retrieval.empty.count").increment();
            }
            return result;
        } finally {
            sample.stop(Timer.builder("conversation.retrieval.latency")
                    .description("Conversation retrieval orchestrator latency.")
                    .register(meterRegistry));
        }
    }

    public List<ConversationRetrievalCandidate> readDocument(
            ConversationDocumentReference document,
            Integer afterChunkOrder,
            String afterSegmentId,
            int limit) {
        if (document == null) {
            return List.of();
        }
        return retrievalDocumentContentQueryApi.query(new RetrievalDocumentContentQuery(
                        document.kbId(), document.id(), document.activeIndexGeneration(),
                        afterChunkOrder, afterSegmentId, limit))
                .stream()
                .map(chunk -> toDocumentCandidate(chunk, document))
                .toList();
    }

    public Map<String, String> generateCitationReasons(
            ConversationCitationReasonRequest request) {
        if (request == null) {
            return Map.of();
        }
        return retrievalCitationReasonApi.generate(new RetrievalCitationReasonRequest(
                request.question(), request.rewrittenQuery(), request.answer(),
                request.citations().stream()
                        .map(group -> new RetrievalCitationReasonRequest.CitationGroup(
                                group.citationIndex(), group.assetId(),
                                group.chunks().stream()
                                        .map(chunk -> new RetrievalCitationReasonRequest.CitationChunk(
                                                chunk.segmentId(), chunk.content(), chunk.score(),
                                                chunk.hitSources(), chunk.matchSummary()))
                                        .toList()))
                        .toList()));
    }

    private List<String> resolveSegmentTypes(List<String> preferredModalities) {
        if (preferredModalities == null || preferredModalities.isEmpty()) {
            return List.of();
        }
        List<String> normalized = preferredModalities.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (normalized.size() != 1 || MODALITY_MIXED.equals(normalized.getFirst())) {
            return List.of();
        }
        return switch (normalized.getFirst()) {
            case "TEXT" -> List.of("TEXT_CHUNK");
            case "IMAGE" -> List.of("IMAGE_OCR_BLOCK", "IMAGE_VISUAL");
            default -> List.of();
        };
    }

    private List<ConversationRetrievalCandidate> toCandidates(RetrievalHit item) {
        if (item == null) {
            return List.of();
        }
        if (item.topChunks() == null || item.topChunks().isEmpty()) {
            return List.of(toCandidate(item, null));
        }
        return item.topChunks().stream()
                .filter(Objects::nonNull)
                .map(topChunk -> toCandidate(item, topChunk))
                .toList();
    }

    private ConversationRetrievalCandidate toCandidate(RetrievalHit item, RetrievalTopChunk topChunk) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(topChunk == null ? item.segmentId() : topChunk.segmentId())
                .kbId(topChunk == null || !StringUtils.hasText(topChunk.kbId()) ? item.kbId() : topChunk.kbId())
                .assetId(item.assetId())
                .assetType(item.assetType())
                .resultType(item.resultType())
                .segmentType(topChunk == null ? item.segmentType() : topChunk.segmentType())
                .title(topChunk == null ? item.title() : topChunk.title())
                .sourceRef(topChunk == null || !StringUtils.hasText(topChunk.sourceRef())
                        ? item.sourceRef() : topChunk.sourceRef())
                .content(topChunk == null ? item.content() : topChunk.content())
                .snippet(topChunk == null ? item.snippet() : topChunk.snippet())
                .score(topChunk == null ? item.score() : topChunk.score())
                .pageNo(topChunk == null ? item.pageNo() : topChunk.pageNo())
                .anchor(toCandidateAnchor(topChunk == null ? item.anchor() : topChunk.anchor()))
                .explain(toCandidateExplain(topChunk == null
                        ? item.explain() : topChunk.explain()))
                .build();
    }

    private ConversationRetrievalCandidate.Anchor toCandidateAnchor(RetrievalAnchor source) {
        if (source == null) {
            return null;
        }
        return ConversationRetrievalCandidate.Anchor.builder()
                .pageNo(source.pageNo())
                .chunkOrder(source.chunkOrder())
                .bbox(source.bbox())
                .imageWidth(source.imageWidth())
                .imageHeight(source.imageHeight())
                .build();
    }

    private ConversationRetrievalCandidate.Explain toCandidateExplain(RetrievalExplain source) {
        if (source == null) {
            return null;
        }
        return ConversationRetrievalCandidate.Explain.builder()
//                .strategyEffective(source.getStrategyEffective())
                .hitSources(source.hitSources() == null ? List.of() : List.copyOf(source.hitSources()))
                .build();
    }

    private ConversationRetrievalCandidate toDocumentCandidate(
            RetrievalDocumentChunk chunk,
            ConversationDocumentReference document) {
        String sourceRef = StringUtils.hasText(document.fileName())
                ? document.fileName()
                : StringUtils.hasText(chunk.sourceRef()) ? chunk.sourceRef() : document.title();
        return ConversationRetrievalCandidate.builder()
                .segmentId(chunk.segmentId())
                .kbId(chunk.kbId())
                .assetId(chunk.assetId())
                .assetType(chunk.assetType())
                .segmentType(chunk.segmentType())
                .sourceRef(sourceRef)
                .title(chunk.title())
                .content(chunk.content())
                .pageNo(chunk.pageNo())
                .anchor(ConversationRetrievalCandidate.Anchor.builder()
                        .pageNo(chunk.pageNo())
                        .chunkOrder(chunk.chunkOrder())
                        .bbox(chunk.bbox())
                        .build())
                .build();
    }

}
