package com.anchr.core.conversation.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.acl.ConversationActivityAcl;
import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.agent.AgentConversationCleanupService;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationSessionPosition;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.anchr.core.conversation.application.constant.ConversationConstant.DEFAULT_SESSION_LIST_LIMIT;
import static com.anchr.core.conversation.application.constant.ConversationConstant.MAX_SESSION_CURSOR_UPDATED_AT;
import static com.anchr.core.conversation.application.constant.ConversationConstant.MAX_SESSION_LIST_CURSOR_LENGTH;
import static com.anchr.core.conversation.application.constant.ConversationConstant.MAX_SESSION_LIST_LIMIT;
import static com.anchr.core.conversation.application.constant.ConversationConstant.SESSION_LIST_CURSOR_VERSION;
import static com.anchr.core.conversation.application.constant.ConversationConstant.SINGLE_USER_ID;

@Component
@RequiredArgsConstructor
final class ConversationSessionUseCase {

    private final ConversationRepository conversationRepository;
    private final ConversationKnowledgeAcl conversationKnowledgeAcl;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AgentConversationCleanupService agentConversationCleanupService;
    private final ConversationActivityAcl conversationActivityAcl;

    ConversationSessionDTO create(ConversationCreateRequestDTO request) {
        long now = System.currentTimeMillis();
        ConversationSession session = ConversationSession.createActive(
                newSessionId(),
                SINGLE_USER_ID,
                safeTrim(request.getTitle()),
                now
        );
        session.setKbScope(conversationKnowledgeAcl.resolveVisibleKbIds(request.getKbIds()));
        conversationRepository.createSession(session);
        meterRegistry.counter("conversation.created.count").increment();
        return toDto(session);
    }

    ConversationSessionDTO get(String sessionId) {
        return toDto(loadSessionOrThrow(sessionId));
    }

    ConversationSessionListDTO list(Integer limit, String cursor) {
        int boundedLimit = normalizeLimit(limit);
        ConversationSessionPosition before = decodeCursor(cursor);
        List<ConversationSession> candidates = conversationRepository.findSessionPage(
                SINGLE_USER_ID, before, boundedLimit + 1);
        boolean hasMore = candidates.size() > boundedLimit;
        List<ConversationSession> page = candidates.stream().limit(boundedLimit).toList();

        ConversationSessionListDTO response = new ConversationSessionListDTO();
        response.setItems(page.stream().map(this::toDto).toList());
        if (hasMore && !page.isEmpty()) {
            ConversationSession last = page.getLast();
            response.setNextCursor(encodeCursor(
                    new ConversationSessionPosition(last.getSessionId(), last.getUpdatedAt())));
        }
        return response;
    }

    ConversationSessionDTO rename(String sessionId, ConversationRenameRequestDTO request) {
        conversationRepository.renameSession(
                sessionId, request.getTitle().trim(), System.currentTimeMillis());
        return toDto(loadSessionOrThrow(sessionId));
    }

    void delete(String sessionId) {
        loadSessionOrThrow(sessionId);
        agentConversationCleanupService.cancelRunning(sessionId);
        conversationRepository.deleteSession(sessionId);
        agentConversationCleanupService.deleteRecords(sessionId);
        conversationActivityAcl.deleteBySessionId(sessionId);
    }

    private ConversationSession loadSessionOrThrow(String sessionId) {
        return conversationRepository.findSession(sessionId)
                .orElseThrow(() -> new BusinessException(ApiError.CONVERSATION_SESSION_NOT_FOUND));
    }

    private ConversationSessionDTO toDto(ConversationSession session) {
        ConversationSessionDTO dto = new ConversationSessionDTO();
        dto.setSessionId(session.getSessionId());
        dto.setUserId(session.getUserId());
        dto.setTitle(session.getTitle());
        dto.setStatus(session.getStatus().name());
        dto.setKbScope(session.getKbScope() == null ? List.of() : session.getKbScope());
        dto.setAssetScope(session.getAssetScope() == null ? List.of() : session.getAssetScope());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        dto.setExpiresAt(session.getExpiresAt());
        return dto;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_SESSION_LIST_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_SESSION_LIST_LIMIT));
    }

    private String encodeCursor(ConversationSessionPosition position) {
        try {
            var payload = objectMapper.createObjectNode();
            payload.put("version", SESSION_LIST_CURSOR_VERSION);
            payload.put("updatedAt", position.updatedAt());
            payload.put("sessionId", position.sessionId());
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to encode session list cursor", exception);
        }
    }

    private ConversationSessionPosition decodeCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        try {
            String normalized = cursor.trim();
            if (normalized.length() > MAX_SESSION_LIST_CURSOR_LENGTH) {
                throw invalidCursor();
            }
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(normalized));
            JsonNode version = payload == null ? null : payload.get("version");
            JsonNode updatedAt = payload == null ? null : payload.get("updatedAt");
            JsonNode sessionId = payload == null ? null : payload.get("sessionId");
            if (version == null || !version.isIntegralNumber()
                    || !version.canConvertToInt()
                    || version.intValue() != SESSION_LIST_CURSOR_VERSION
                    || updatedAt == null || !updatedAt.isIntegralNumber()
                    || !updatedAt.canConvertToLong()
                    || updatedAt.longValue() < 0
                    || updatedAt.longValue() > MAX_SESSION_CURSOR_UPDATED_AT
                    || sessionId == null || !sessionId.isTextual()
                    || !StringUtils.hasText(sessionId.textValue())
                    || !sessionId.textValue().equals(sessionId.textValue().trim())
                    || sessionId.textValue().length() > 64) {
                throw invalidCursor();
            }
            return new ConversationSessionPosition(
                    sessionId.textValue(), updatedAt.longValue());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private BusinessException invalidCursor() {
        return new BusinessException(ApiError.INVALID_REQUEST, "cursor is invalid");
    }

    private String safeTrim(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String newSessionId() {
        return "cvs_" + UUID.randomUUID().toString().replace("-", "");
    }
}
