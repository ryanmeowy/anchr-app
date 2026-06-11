package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.settings.application.model.SearchSetting;
import com.anchr.core.settings.domain.model.AppSetting;
import com.anchr.core.settings.domain.repository.AppSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchSettingServiceImplTest {

    private final AppSettingRepository appSettingRepository = mock(AppSettingRepository.class);
    private final AppSearchProperties properties = new AppSearchProperties();
    private final SearchSettingServiceImpl service =
            new SearchSettingServiceImpl(appSettingRepository, properties, new ObjectMapper());

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void get_shouldApplyPersistedSettingToRuntimeProperties() {
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a", "OWNER"));
        when(appSettingRepository.find("ws-a", SearchSettingServiceImpl.SETTING_KEY))
                .thenReturn(Optional.of(setting("{\"topK\":15,\"rerankWindow\":30,\"rrfK\":55,\"minScore\":0.8}")));

        SearchSetting result = service.get();

        assertThat(result.getTopK()).isEqualTo(15);
        assertThat(properties.getPage().getDefaultPageSize()).isEqualTo(15);
        assertThat(properties.getRerank().getWindowSize()).isEqualTo(30);
        assertThat(properties.getRrf().getRankConstant()).isEqualTo(55);
        assertThat(properties.getQualityAbsoluteMinScore()).isEqualTo(0.8d);
    }

    @Test
    void update_shouldPersistAndHotApplyAllowedFields() {
        UserContextHolder.set(new RequestUserContext("ws-a", "user-a", "OWNER"));
        when(appSettingRepository.upsert(eq("ws-a"), eq(SearchSettingServiceImpl.SETTING_KEY),
                contains("\"topK\":20"), eq("user-a"))).thenReturn(setting("{}"));

        SearchSetting result = service.update(20, 40, 70, 0.77d);

        assertThat(result.getTopK()).isEqualTo(20);
        assertThat(properties.getPage().getDefaultPageSize()).isEqualTo(20);
        verify(appSettingRepository).upsert(eq("ws-a"), eq(SearchSettingServiceImpl.SETTING_KEY),
                contains("\"rerankWindow\":40"), eq("user-a"));
    }

    private AppSetting setting(String value) {
        return AppSetting.builder()
                .id("set-1")
                .workspaceId("ws-a")
                .settingKey(SearchSettingServiceImpl.SETTING_KEY)
                .settingValue(value)
                .version(1)
                .updatedBy("user-a")
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
