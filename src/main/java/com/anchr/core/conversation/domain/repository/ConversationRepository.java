package com.anchr.core.conversation.domain.repository;

import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import com.anchr.core.conversation.domain.model.ConversationTurnPosition;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for conversation session and turns.
 */
public interface ConversationRepository {

    void saveSession(ConversationSession session);

    Optional<ConversationSession> findSession(String sessionId);

    /**
     * Locks and verifies an active session before a generated result is persisted.
     * The default keeps non-database adapters and legacy tests compatible.
     */
    default boolean lockActiveSession(String sessionId) {
        return findSession(sessionId).isPresent();
    }

    List<ConversationSession> findRecentSessions(String userId, int limit);

    void deleteSession(String sessionId);

    void saveTurn(ConversationTurn turn);

    Optional<ConversationTurn> findTurn(String sessionId, String turnId);

    List<ConversationTurn> findRecentTurns(String sessionId, int limit);

    /**
     * Returns only the stable sort position of a turn. Database adapters should override this
     * to avoid loading large answer and JSON columns.
     */
    default Optional<ConversationTurnPosition> findTurnPosition(String sessionId, String turnId) {
        return findTurn(sessionId, turnId)
                .map(turn -> new ConversationTurnPosition(turn.getTurnId(), turn.getCreatedAt()));
    }

    /**
     * Returns one history page in newest-first order. The default keeps in-memory adapters and
     * legacy tests compatible; database adapters should push the keyset boundary into SQL.
     */
    default List<ConversationTurn> findTurnPage(String sessionId,
                                                ConversationTurnPosition before,
                                                int limit) {
        return findRecentTurns(sessionId, Integer.MAX_VALUE).stream()
                .filter(turn -> before == null
                        || turn.getCreatedAt() < before.createdAt()
                        || (turn.getCreatedAt() == before.createdAt()
                        && turn.getTurnId().compareTo(before.turnId()) < 0))
                .sorted(Comparator.comparingLong(ConversationTurn::getCreatedAt)
                        .thenComparing(ConversationTurn::getTurnId)
                        .reversed())
                .limit(Math.max(1, limit))
                .toList();
    }
}
