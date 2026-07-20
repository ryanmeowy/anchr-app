package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ConversationMapper {
    int upsertSession(ConversationSessionRecord record);

    Optional<ConversationSessionRecord> findSession(@Param("sessionId") String sessionId);

    Integer lockActiveSession(@Param("sessionId") String sessionId);

    List<ConversationSessionRecord> findRecentSessions(@Param("userId") String userId,
                                                       @Param("limit") int limit);

    int softDeleteSession(@Param("sessionId") String sessionId);

    int softDeleteTurns(@Param("sessionId") String sessionId);

    int upsertTurn(ConversationTurnRecord record);

    Optional<ConversationTurnRecord> findTurn(@Param("sessionId") String sessionId,
                                              @Param("turnId") String turnId);

    List<ConversationTurnRecord> findRecentTurns(@Param("sessionId") String sessionId,
                                                 @Param("limit") int limit);

    Optional<ConversationTurnRecord> findTurnPosition(@Param("sessionId") String sessionId,
                                                      @Param("turnId") String turnId);

    List<ConversationTurnRecord> findTurnPage(@Param("sessionId") String sessionId,
                                              @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                              @Param("beforeTurnId") String beforeTurnId,
                                              @Param("limit") int limit);
}
