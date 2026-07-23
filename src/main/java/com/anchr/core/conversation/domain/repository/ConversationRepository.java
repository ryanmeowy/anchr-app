package com.anchr.core.conversation.domain.repository;

import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationSessionPosition;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.model.ConversationTurnPosition;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for conversation session and turns.
 */
public interface ConversationRepository {

    void createSession(ConversationSession session);

    Optional<ConversationSession> findSession(String sessionId);

    boolean lockActiveSession(String sessionId);

    List<ConversationSession> findSessionPage(String userId,
                                              ConversationSessionPosition before,
                                              int limit);

    void renameSession(String sessionId, String title, long renamedAt);

    void touchSessionIfNewer(String sessionId, long requestStartedAt);

    boolean updateAutoTitleIfUnchanged(String sessionId,
                                       String expectedTitle,
                                       String generatedTitle,
                                       long requestStartedAt);

    void deleteSession(String sessionId);

    void saveTurn(ConversationTurn turn);

    Optional<ConversationTurn> findTurn(String sessionId, String turnId);

    List<ConversationTurn> findRecentTurns(String sessionId, int limit);

    Optional<ConversationTurnPosition> findTurnPosition(String sessionId, String turnId);

    List<ConversationTurn> findTurnPage(String sessionId,
                                        ConversationTurnPosition before,
                                        int limit);
}
