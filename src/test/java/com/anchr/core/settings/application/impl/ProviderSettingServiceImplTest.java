package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.settings.application.model.ProviderSwitchResult;
import com.anchr.core.settings.application.provider.ProviderIdentity;
import com.anchr.core.settings.domain.model.AppSetting;
import com.anchr.core.settings.domain.model.ProviderSetting;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.domain.repository.AppSettingRepository;
import com.anchr.core.settings.domain.repository.ProviderConfigVersionRepository;
import com.anchr.core.settings.domain.repository.ProviderSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSettingServiceImplTest {

    private final AppSettingRepository appSettingRepository = mock(AppSettingRepository.class);
    private final ProviderSettingRepository providerSettingRepository = mock(ProviderSettingRepository.class);
    private final ProviderConfigVersionRepository versionRepository = mock(ProviderConfigVersionRepository.class);
    private final ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(List.of(
            new TestProvider(ProviderType.GENERATION, "local")));
    private final ProviderSettingServiceImpl service = new ProviderSettingServiceImpl(
            appSettingRepository, providerSettingRepository, versionRepository, registry, new ObjectMapper());

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void switchProvider_shouldPersistSelectionAndVersionSnapshot() {
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a"));
        when(appSettingRepository.find("ws-a", ProviderSelectionService.SETTING_KEY))
                .thenReturn(Optional.of(AppSetting.builder()
                        .id("set-1")
                        .workspaceId("ws-a")
                        .settingKey(ProviderSelectionService.SETTING_KEY)
                        .settingValue("{\"ocr\":\"aliyun\"}")
                        .version(1)
                        .updatedBy("user-a")
                        .updatedAt(LocalDateTime.now())
                        .build()));
        when(providerSettingRepository.upsert(eq("ws-a"), eq(ProviderType.GENERATION), eq("local"),
                eq("{}"), eq(null), eq(true), eq("user-a"))).thenReturn(ProviderSetting.builder()
                .id("provs-1")
                .workspaceId("ws-a")
                .providerType(ProviderType.GENERATION)
                .providerName("local")
                .configValue("{}")
                .enabled(true)
                .version(2)
                .updatedBy("user-a")
                .updatedAt(LocalDateTime.now())
                .build());

        ProviderSwitchResult result = service.switchProvider(ProviderType.GENERATION, "local");

        assertThat(result.isEffectiveImmediately()).isTrue();
        assertThat(result.getVersion()).isEqualTo(2);
        verify(versionRepository).save(eq("provs-1"), eq(2), contains("\"providerName\":\"local\""), eq("user-a"));
        verify(appSettingRepository).upsert(eq("ws-a"), eq(ProviderSelectionService.SETTING_KEY),
                contains("\"generation\":\"local\""), eq("user-a"));
    }

    private record TestProvider(ProviderType providerType, String providerName) implements ProviderIdentity {
    }
}
