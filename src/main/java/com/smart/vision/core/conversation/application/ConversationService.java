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

    ConversationSessionDTO getSession(String sessionId);

    ConversationSessionListDTO listSessions(Integer limit, String cursor);

    ConversationSessionDTO renameSession(String sessionId, ConversationRenameRequestDTO request);

    void deleteSession(String sessionId);

    ConversationMessageResponseDTO createMessage(String sessionId, ConversationMessageRequestDTO request);

    ConversationTurnListDTO listMessages(String sessionId, Integer limit, String beforeTurnId);
}
