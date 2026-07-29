package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ConversationMapper {
    int insertSession(ConversationSessionRecord record);

    Optional<ConversationSessionRecord> findSession(@Param("sessionId") String sessionId);

    Integer lockActiveSession(@Param("sessionId") String sessionId);

    List<ConversationSessionRecord> findSessionPage(@Param("userId") String userId,
                                                    @Param("beforeUpdatedAt") LocalDateTime beforeUpdatedAt,
                                                    @Param("beforeSessionId") String beforeSessionId,
                                                    @Param("limit") int limit);

    int renameSession(@Param("sessionId") String sessionId,
                      @Param("title") String title,
                      @Param("renamedAt") LocalDateTime renamedAt);

    int touchSessionIfNewer(@Param("sessionId") String sessionId,
                            @Param("requestStartedAt") LocalDateTime requestStartedAt);

    int updateAutoTitleIfUnchanged(@Param("sessionId") String sessionId,
                                   @Param("expectedTitle") String expectedTitle,
                                   @Param("generatedTitle") String generatedTitle,
                                   @Param("requestStartedAt") LocalDateTime requestStartedAt);

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
