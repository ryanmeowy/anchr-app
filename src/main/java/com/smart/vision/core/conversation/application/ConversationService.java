package com.smart.vision.core.conversation.application;

import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;

/**
 * Application service for conversation APIs.
 */
public interface ConversationService {

    ConversationSessionDTO createSession(ConversationCreateRequestDTO request);

    ConversationSessionDTO createSession(String userId, ConversationCreateRequestDTO request);

    ConversationSessionDTO getSession(String sessionId);

    ConversationSessionDTO getSession(String userId, String sessionId);

    ConversationSessionListDTO listSessions(String userId, Integer limit, String cursor);

    ConversationSessionDTO renameSession(String userId, String sessionId, ConversationRenameRequestDTO request);

    void deleteSession(String userId, String sessionId);

    ConversationMessageResponseDTO createMessage(String sessionId, ConversationMessageRequestDTO request);

    ConversationMessageResponseDTO createMessage(String userId, String sessionId, ConversationMessageRequestDTO request);

    ConversationTurnListDTO listMessages(String sessionId, Integer limit, String beforeTurnId);

    ConversationTurnListDTO listMessages(String userId, String sessionId, Integer limit, String beforeTurnId);
}
