package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.anchr.core.search.domain.util.ScoreUtil.mapScoreToPercentage;

@Component
public class SearchResultConvertor {
    public List<ImageSearchResultDTO> convert2Doc(SearchResponse<ImageDocument> response) {
        List<Hit<ImageDocument>> hits = Optional.ofNullable(response)
                .map(SearchResponse::hits)
                .map(HitsMetadata::hits)
                .orElse(Collections.emptyList());

        return hits.stream()
                .filter(hit -> hit.source() != null)
                .filter(hit -> hit.id() != null)
                .filter(hit -> hit.score() != null)
                .map(hit -> {
                    ImageDocument doc = hit.source();
                    doc.setId(Long.parseLong(hit.id()));
                    double rawScore = hit.score();
                    Map<String, String> highlightMap = extractHighlightMap(hit);
                    return ImageSearchResultDTO.builder()
                            .document(doc)
                            .rawScore(rawScore)
                            .score(mapScoreToPercentage(rawScore))
                            .sortValues(hit.sort().stream().map(this::unwrapFieldValue).collect(Collectors.toList()))
                            .highlights(highlightMap)
                            .highlightFields(List.copyOf(highlightMap.keySet()))
                            .build();
                }).collect(Collectors.toList());
    }

    private Map<String, String> extractHighlightMap(Hit<ImageDocument> hit) {
        Map<String, List<String>> highlightByField = hit.highlight();
        if (highlightByField == null || highlightByField.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : highlightByField.entrySet()) {
            String field = entry.getKey();
            if (!StringUtils.hasText(field) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String snippet = entry.getValue().stream()
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            if (StringUtils.hasText(snippet)) {
                normalized.put(field, snippet);
            }
        }
        return normalized;
    }

    private Object unwrapFieldValue(FieldValue fv) {
        if (fv == null) return null;
        if (fv.isDouble()) return fv.doubleValue();
        if (fv.isLong()) return String.valueOf(fv.longValue());
        if (fv.isString()) return fv.stringValue();
        if (fv.isBoolean()) return fv.booleanValue();
        return fv._get();
    }
}
