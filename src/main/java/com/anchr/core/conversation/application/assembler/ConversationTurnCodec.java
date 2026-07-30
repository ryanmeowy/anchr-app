package com.anchr.core.conversation.application.assembler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ResultCardDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConversationTurnCodec {

    private final ObjectMapper objectMapper;

    public String serializeCitations(List<ConversationCitation> citations) {
        try {
            return objectMapper.writeValueAsString(citations == null ? List.of() : citations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize citations.", e);
        }
    }

    public String serializeResultCards(List<ResultCardDTO> resultCards) {
        try {
            return objectMapper.writeValueAsString(resultCards == null ? List.of() : resultCards);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize result cards.", e);
        }
    }

    public String serializeKbScope(List<String> kbScope) {
        try {
            return objectMapper.writeValueAsString(kbScope == null ? List.of() : kbScope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize kb scope.", e);
        }
    }

    public String serializeAssetScope(List<String> assetScope) {
        try {
            return objectMapper.writeValueAsString(assetScope == null ? List.of() : assetScope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize asset scope.", e);
        }
    }

    public List<ConversationTurnDTO.CitationDTO> parseCitations(String citationsJson) {
        if (!StringUtils.hasText(citationsJson)) {
            return List.of();
        }
        try {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ConversationCitation.class);
            List<ConversationCitation> citations = objectMapper.readValue(citationsJson, listType);
            return toCitationDTOs(citations);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<String> parseAssetScope(String assetScopeJson) {
        if (!StringUtils.hasText(assetScopeJson)) {
            return List.of();
        }
        try {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class);
            return objectMapper.readValue(assetScopeJson, listType);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<ConversationTurnDTO.CitationDTO> toCitationDTOs(List<ConversationCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        Map<String, ConversationTurnDTO.CitationDTO> citationGroups = new LinkedHashMap<>();
        for (ConversationCitation citation : citations) {
            if (citation == null) {
                continue;
            }
            String groupKey = StringUtils.hasText(citation.getAssetId())
                    ? "asset:" + citation.getAssetId()
                    : "segment:" + citation.getSegmentId();
            ConversationTurnDTO.CitationDTO group = citationGroups.computeIfAbsent(groupKey, ignored -> {
                ConversationTurnDTO.CitationDTO dto = new ConversationTurnDTO.CitationDTO();
                dto.setCitationIndex(citation.getAssetCitationIndex() == null
                        ? citationGroups.size() + 1 : citation.getAssetCitationIndex());
                dto.setFileName(citation.getFileName());
                dto.setKbId(citation.getKbId());
                dto.setAssetId(citation.getAssetId());
                return dto;
            });
            boolean alreadyPresent = group.getChunks().stream()
                    .anyMatch(chunk -> java.util.Objects.equals(chunk.getSegmentId(), citation.getSegmentId()));
            if (!alreadyPresent) {
                group.getChunks().add(toCitationChunkDTO(citation));
            }
        }
        Comparator<ConversationTurnDTO.CitationChunkDTO> documentOrder = Comparator
                .comparing(ConversationTurnDTO.CitationChunkDTO::getPageNo,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(ConversationTurnDTO.CitationChunkDTO::getChunkOrder,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(ConversationTurnDTO.CitationChunkDTO::getSegmentId,
                        Comparator.nullsLast(String::compareTo));
        citationGroups.values().forEach(group -> {
            boolean indexed = group.getChunks().stream().anyMatch(chunk -> chunk.getSegmentIndex() != null);
            group.getChunks().sort(indexed
                    ? Comparator.comparing(ConversationTurnDTO.CitationChunkDTO::getSegmentIndex,
                            Comparator.nullsLast(Integer::compareTo)).thenComparing(documentOrder)
                    : documentOrder);
        });
        return new ArrayList<>(citationGroups.values());
    }

    private ConversationTurnDTO.CitationChunkDTO toCitationChunkDTO(ConversationCitation citation) {
        ConversationTurnDTO.CitationChunkDTO chunk = new ConversationTurnDTO.CitationChunkDTO();
        chunk.setSegmentId(citation.getSegmentId());
        chunk.setSegmentIndex(citation.getSegmentCitationIndex());
        if (citation.getAssetCitationIndex() != null && citation.getSegmentCitationIndex() != null) {
            chunk.setCitationLabel(citation.getAssetCitationIndex() + "-" + citation.getSegmentCitationIndex());
        }
        chunk.setPageNo(citation.getPageNo());
        chunk.setChunkOrder(citation.getChunkOrder());
        chunk.setTitle(citation.getTitle());
        chunk.setContent(citation.getContent());
        chunk.setSnippet(citation.getSnippet());
        chunk.setHitType(citation.getHitType());
        chunk.setAnchor(citation.getAnchor());
        chunk.setWhy(citation.getWhy());
        return chunk;
    }
}
