package com.anchr.core.settings.interfaces.rest;

import com.anchr.core.common.exception.GlobalExceptionHandler;
import com.anchr.core.settings.application.PreferenceSettingService;
import com.anchr.core.settings.application.ProviderConnectionTestService;
import com.anchr.core.settings.application.ProviderSettingService;
import com.anchr.core.settings.application.SearchSettingService;
import com.anchr.core.settings.application.SettingsQueryService;
import com.anchr.core.settings.application.model.PreferenceSetting;
import com.anchr.core.settings.application.model.ProviderSwitchResult;
import com.anchr.core.settings.application.model.SearchSetting;
import com.anchr.core.settings.domain.model.PreferenceTheme;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.interfaces.rest.dto.CapabilitiesDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderConnectionTestResultDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderListDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettingsApiControllerTest {

    @Mock
    private SettingsQueryService settingsQueryService;
    @Mock
    private SearchSettingService searchSettingService;
    @Mock
    private ProviderConnectionTestService providerConnectionTestService;
    @Mock
    private PreferenceSettingService preferenceSettingService;
    @Mock
    private ProviderSettingService providerSettingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SettingsApiController(
                        settingsQueryService,
                        searchSettingService,
                        providerConnectionTestService,
                        preferenceSettingService,
                        providerSettingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void capabilities_shouldReturnEnvelope() throws Exception {
        when(settingsQueryService.capabilities()).thenReturn(CapabilitiesDTO.builder()
                .generation(CapabilityDTO.builder().enabled(true).provider("aliyun").build())
                .build());

        mockMvc.perform(get("/api/v1/settings/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.generation.provider").value("aliyun"));
    }

    @Test
    void updateSearch_shouldPassHotFields() throws Exception {
        when(searchSettingService.update(eq(20), eq(40), eq(60), eq(0.75d)))
                .thenReturn(SearchSetting.builder()
                        .topK(20)
                        .rerankWindow(40)
                        .rrfK(60)
                        .minScore(0.75d)
                        .hotUpdateSupported(true)
                        .build());

        mockMvc.perform(patch("/api/v1/settings/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\":20,\"rerankWindow\":40,\"rrfK\":60,\"minScore\":0.75}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topK").value(20))
                .andExpect(jsonPath("$.data.hotUpdateSupported").value(true));
    }

    @Test
    void testConnection_shouldReturnReadableFailurePayload() throws Exception {
        when(providerConnectionTestService.test(eq(ProviderType.GENERATION), eq("aliyun")))
                .thenReturn(ProviderConnectionTestResultDTO.builder()
                        .providerType("GENERATION")
                        .providerName("aliyun")
                        .success(false)
                        .code("TEST_INPUT_MISSING")
                        .message("Generation test prompt is not configured.")
                        .build());

        mockMvc.perform(post("/api/v1/settings/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerType\":\"GENERATION\",\"providerName\":\"aliyun\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.code").value("TEST_INPUT_MISSING"));
    }

    @Test
    void preferences_shouldReturnTheme() throws Exception {
        when(preferenceSettingService.update(PreferenceTheme.DARK))
                .thenReturn(PreferenceSetting.builder().theme(PreferenceTheme.DARK).build());

        mockMvc.perform(patch("/api/v1/settings/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"DARK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("DARK"));
    }

    @Test
    void switchProvider_shouldReturnVersionAndWarnings() throws Exception {
        when(providerSettingService.switchProvider(ProviderType.GENERATION, "local"))
                .thenReturn(ProviderSwitchResult.builder()
                        .providerType(ProviderType.GENERATION)
                        .providerName("local")
                        .version(2)
                        .effectiveImmediately(true)
                        .warnings(List.of())
                        .build());

        mockMvc.perform(patch("/api/v1/settings/providers/selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerType\":\"GENERATION\",\"providerName\":\"local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerName").value("local"))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void providers_shouldReturnEnvelope() throws Exception {
        when(settingsQueryService.providers()).thenReturn(ProviderListDTO.builder()
                .providers(List.of())
                .build());

        mockMvc.perform(get("/api/v1/settings/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
