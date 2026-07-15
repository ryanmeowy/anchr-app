package com.anchr.core.conversation.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationSessionStatus;
import com.anchr.core.conversation.domain.model.ConversationTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class ConversationRepositoryImplTest {

    @Mock
    private ConversationMapper mapper;

    private ConversationRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new ConversationRepositoryImpl(mapper, new ObjectMapper());
    }

    @Test
    void shouldMapSessionScopesTimesAndPermanentRetention() {
        long timestamp = 1_700_000_000_123L;
        ConversationSession session = ConversationSession.createActive("cvs_1", "single_user", "title", timestamp);
        session.setKbScope(List.of("kb_1", "kb_2"));
        session.setAssetScope(List.of("asset_1"));

        repository.saveSession(session);

        ArgumentCaptor<ConversationSessionRecord> captor = ArgumentCaptor.forClass(ConversationSessionRecord.class);
        verify(mapper).upsertSession(captor.capture());
        assertThat(captor.getValue().getKbScope()).isEqualTo("[\"kb_1\",\"kb_2\"]");
        assertThat(captor.getValue().getAssetScope()).isEqualTo("[\"asset_1\"]");

        ConversationSessionRecord record = captor.getValue();
        when(mapper.findSession("cvs_1")).thenReturn(Optional.of(record));
        ConversationSession restored = repository.findSession("cvs_1").orElseThrow();

        assertThat(restored.getStatus()).isEqualTo(ConversationSessionStatus.ACTIVE);
        assertThat(restored.getKbScope()).containsExactly("kb_1", "kb_2");
        assertThat(restored.getAssetScope()).containsExactly("asset_1");
        assertThat(restored.getCreatedAt()).isEqualTo(timestamp);
        assertThat(restored.getExpiresAt()).isNull();
    }

    @Test
    void shouldPreserveMapperOrderingAndBoundLimits() {
        ConversationSessionRecord first = record("cvs_2", 2_000L);
        ConversationSessionRecord second = record("cvs_1", 1_000L);
        when(mapper.findRecentSessions("single_user", 200)).thenReturn(List.of(first, second));

        assertThat(repository.findRecentSessions("single_user", 999))
                .extracting(ConversationSession::getSessionId)
                .containsExactly("cvs_2", "cvs_1");
    }

    @Test
    void shouldSoftDeleteTurnsBeforeSession() {
        repository.deleteSession("cvs_1");

        var ordered = inOrder(mapper);
        ordered.verify(mapper).softDeleteTurns("cvs_1");
        ordered.verify(mapper).softDeleteSession("cvs_1");
    }

    @Test
    void shouldPersistIntentAndDefaultLegacyTurns() {
        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId("turn_1");
        turn.setSessionId("cvs_1");
        turn.setIntentType("CHAT");
        turn.setIntentConfidence(0.98D);
        turn.setIntentReason("explicit_chat_rule");
        turn.setIntentSource("RULE");
        turn.setIntentFallback(false);
        turn.setCreatedAt(1_700_000_000_123L);

        repository.saveTurn(turn);

        ArgumentCaptor<ConversationTurnRecord> captor = ArgumentCaptor.forClass(ConversationTurnRecord.class);
        verify(mapper).upsertTurn(captor.capture());
        assertThat(captor.getValue().getIntentType()).isEqualTo("CHAT");
        assertThat(captor.getValue().getIntentConfidence()).isEqualTo(0.98D);
        assertThat(captor.getValue().getIntentSource()).isEqualTo("RULE");

        ConversationTurn legacy = new ConversationTurn();
        legacy.setTurnId("turn_legacy");
        legacy.setSessionId("cvs_1");
        legacy.setCreatedAt(1_700_000_000_124L);
        repository.saveTurn(legacy);

        verify(mapper).upsertTurn(org.mockito.ArgumentMatchers.argThat(record ->
                "KB_QUERY".equals(record.getIntentType()) && "LEGACY".equals(record.getIntentSource())));
    }

    private ConversationSessionRecord record(String sessionId, long timestamp) {
        ConversationSessionRecord record = new ConversationSessionRecord();
        record.setSessionId(sessionId);
        record.setUserId("single_user");
        record.setStatus("ACTIVE");
        record.setKbScope("[]");
        record.setAssetScope("[]");
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        record.setCreatedAt(time);
        record.setUpdatedAt(time);
        return record;
    }
}
