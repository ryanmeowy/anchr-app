package com.anchr.core.kb.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private IdGen idGen;

    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("user-a", "OWNER"));
        service = new KnowledgeBaseServiceImpl(
                knowledgeBaseRepository,
                assetRepository,
                outboxEventRepository,
                idGen,
                new ObjectMapper());
        when(knowledgeBaseRepository.findActiveById("kb-1"))
                .thenReturn(Optional.of(KnowledgeBase.builder()
                        .id("kb-1")
                        .status(KnowledgeBaseStatus.ACTIVE)
                        .build()));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void deleteDocument_shouldSoftDeleteRefreshStatsAndWriteOutboxEvent() throws Exception {
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any()))
                .thenReturn(true);

        service.deleteDocument("kb-1", "asset-1");

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.DELETE_ASSET);
        assertThat(event.getAggregateType()).isEqualTo("ASSET");
        assertThat(event.getAggregateId()).isEqualTo("asset-1");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getCreatedBy()).isEqualTo("user-a");
        assertThat(new ObjectMapper().readTree(event.getPayload()))
                .isEqualTo(new ObjectMapper().readTree("{\"kbId\":\"kb-1\",\"assetId\":\"asset-1\"}"));
        verify(knowledgeBaseRepository).refreshDocumentStats("kb-1", "user-a", false);
    }

    @Test
    void deleteDocument_shouldNotWriteEventWhenDocumentDoesNotExist() {
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.deleteDocument("kb-1", "asset-1"))
                .isInstanceOf(BusinessException.class);

        verify(outboxEventRepository, never()).save(any());
        verify(knowledgeBaseRepository, never()).refreshDocumentStats(any(), any(), eq(false));
    }

    @Test
    void deleteDocument_shouldPropagateOutboxFailureForTransactionRollback() {
        when(assetRepository.markDeleted(eq("kb-1"), eq("asset-1"), eq("user-a"), any()))
                .thenReturn(true);
        doThrow(new IllegalStateException("database unavailable"))
                .when(outboxEventRepository).save(any());

        assertThatThrownBy(() -> service.deleteDocument("kb-1", "asset-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }
}
