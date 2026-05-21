package com.anchr.core.conversation.application;

import com.anchr.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    SseEmitter streamMessage(String sessionId, ConversationMessageRequestDTO request);

    ConversationTurnListDTO listMessages(String sessionId, Integer limit, String beforeTurnId);
}
