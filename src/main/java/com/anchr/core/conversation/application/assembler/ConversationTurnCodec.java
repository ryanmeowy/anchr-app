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
import java.util.List;

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

    public List<ResultCardDTO> parseResultCards(String resultCardsJson) {
        if (!StringUtils.hasText(resultCardsJson)) {
            return List.of();
        }
        try {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ResultCardDTO.class);
            return objectMapper.readValue(resultCardsJson, listType);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<String> parseKbScope(String kbScopeJson) {
        if (!StringUtils.hasText(kbScopeJson)) {
            return List.of();
        }
        try {
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class);
            return objectMapper.readValue(kbScopeJson, listType);
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
        List<ConversationTurnDTO.CitationDTO> citationList = new ArrayList<>();
        for (ConversationCitation citation : citations) {
            if (citation == null) {
                continue;
            }
            ConversationTurnDTO.CitationDTO dto = new ConversationTurnDTO.CitationDTO();
            dto.setFileName(citation.getFileName());
            dto.setPageNo(citation.getPageNo());
            dto.setSnippet(citation.getSnippet());
            dto.setHitType(citation.getHitType());
            dto.setAssetId(citation.getAssetId());
            dto.setSegmentId(citation.getSegmentId());
            dto.setWhy(citation.getWhy());
            citationList.add(dto);
        }
        return citationList;
    }
}
