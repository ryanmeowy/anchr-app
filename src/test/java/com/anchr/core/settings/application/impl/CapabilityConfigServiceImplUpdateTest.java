package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapabilityConfigServiceImplUpdateTest {

    @Mock private CapabilityConfigRepository repository;
    @Mock private AesUtil aesUtil;
    @Mock private IdGen idGen;
    @Mock private CapabilityClientFactory clientFactory;
    @Mock private CapabilityResolver capabilityResolver;
    @Mock private ClientCacheManager clientCacheManager;

    private CapabilityConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("user-a", "ADMIN"));
        service = new CapabilityConfigServiceImpl(
                repository, aesUtil, idGen, clientFactory,
                capabilityResolver, clientCacheManager);
        lenient().when(aesUtil.decrypt(any())).thenReturn("123456789");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "https://new.example,old-model,1024",
            "https://old.example,new-model,1024",
            "https://old.example,old-model,1536"
    })
    void activeFingerprintChangeCreatesDisabledDraftAndKeepsActiveRow(
            String nextBaseUrl, String nextModel, int nextDimension) {
        CapabilityConfig active = config(1L, "https://old.example", "old-model", 1024, true);
        when(repository.findById(1L)).thenReturn(Optional.of(active));
        when(idGen.nextId()).thenReturn(2L);
        when(repository.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.update("EMBEDDING", 1L,
                request(nextBaseUrl, nextModel, nextDimension, null));

        ArgumentCaptor<CapabilityConfig> draft = ArgumentCaptor.forClass(CapabilityConfig.class);
        verify(repository).insert(draft.capture());
        verify(repository, never()).update(any());
        verify(clientCacheManager, never()).put(any(), any());
        verify(clientCacheManager, never()).invalidate(any());
        assertThat(saved.getId()).isEqualTo(2L);
        assertThat(saved.isEnabled()).isFalse();
        assertThat(draft.getValue().getApiKeyEnc()).isEqualTo("encrypted-key");
        assertThat(active.isEnabled()).isTrue();
    }

    @Test
    void apiKeyOnlyChangeUpdatesActiveRowWithoutCreatingDraft() {
        CapabilityConfig active = config(1L, "https://old.example", "old-model", 1024, true);
        when(repository.findById(1L)).thenReturn(Optional.of(active));
        when(aesUtil.encrypt("replacement-key")).thenReturn("new-encrypted-key");
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(capabilityResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING))
                .thenReturn(Optional.empty());

        var saved = service.update("EMBEDDING", 1L,
                request("https://old.example", "old-model", 1024, "replacement-key"));

        verify(repository, never()).insert(any());
        verify(repository).update(any());
        verify(clientCacheManager).invalidate(CapabilityResolver.SLOT_EMBEDDING);
        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void pathCapabilityMustMatchStoredCapability() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                config(1L, "https://old.example", "old-model", 1024, true)));

        assertThatThrownBy(() -> service.update(
                "MULTI_EMBEDDING", 1L,
                request("https://old.example", "old-model", 1024, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");

        verify(repository, never()).insert(any());
        verify(repository, never()).update(any());
    }

    private CapabilityConfig config(Long id, String baseUrl, String model,
                                    int dimension, boolean enabled) {
        return CapabilityConfig.builder()
                .id(id)
                .capability("EMBEDDING")
                .baseUrl(baseUrl)
                .apiKeyEnc("encrypted-key")
                .modelName(model)
                .extraConfig("{\"dimensions\":" + dimension + "}")
                .enabled(enabled)
                .build();
    }

    private CapabilityConfigUpdateRequestDTO request(
            String baseUrl, String model, int dimension, String apiKey) {
        CapabilityConfigUpdateRequestDTO request = new CapabilityConfigUpdateRequestDTO();
        request.setBaseUrl(baseUrl);
        request.setModelName(model);
        request.setApiKey(apiKey);
        request.setExtraConfig(Map.of("dimensions", dimension));
        return request;
    }
}
