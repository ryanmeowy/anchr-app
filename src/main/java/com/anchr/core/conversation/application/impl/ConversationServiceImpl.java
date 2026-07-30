package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.ConversationService;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stable Controller facade over the three concrete Conversation use cases.
 */
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationSessionUseCase sessionUseCase;
    private final ConversationMessageUseCase messageUseCase;
    private final ConversationHistoryQuery historyQuery;

    @Override
    public ConversationSessionDTO createSession(ConversationCreateRequestDTO request) {
        return sessionUseCase.create(request);
    }

    @Override
    public ConversationSessionDTO getSession(String sessionId) {
        return sessionUseCase.get(sessionId);
    }

    @Override
    public ConversationSessionListDTO listSessions(Integer limit, String cursor) {
        return sessionUseCase.list(limit, cursor);
    }

    @Override
    public ConversationSessionDTO renameSession(
            String sessionId,
            ConversationRenameRequestDTO request
    ) {
        return sessionUseCase.rename(sessionId, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId) {
        sessionUseCase.delete(sessionId);
    }

    @Override
    public ConversationMessageResponseDTO createMessage(
            String sessionId,
            ConversationMessageRequestDTO request
    ) {
        return messageUseCase.execute(sessionId, request);
    }

    @Override
    public ConversationTurnDTO getMessage(String sessionId, String turnId) {
        return historyQuery.get(sessionId, turnId);
    }

    @Override
    public ConversationTurnListDTO listMessages(
            String sessionId,
            Integer limit,
            String beforeTurnId
    ) {
        return historyQuery.list(sessionId, limit, beforeTurnId);
    }
}
