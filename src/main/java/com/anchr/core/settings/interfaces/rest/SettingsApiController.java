package com.anchr.core.settings.interfaces.rest;

import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.settings.application.PreferenceSettingService;
import com.anchr.core.settings.application.ProviderSettingService;
import com.anchr.core.settings.application.SearchSettingService;
import com.anchr.core.settings.application.SettingsQueryService;
import com.anchr.core.settings.application.model.ProviderSwitchResult;
import com.anchr.core.settings.application.model.SearchSetting;
import com.anchr.core.settings.domain.model.PreferenceTheme;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.interfaces.rest.dto.CapabilitiesDTO;
import com.anchr.core.settings.interfaces.rest.dto.PreferenceDTO;
import com.anchr.core.settings.interfaces.rest.dto.PreferenceUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderListDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderSwitchRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.ProviderSwitchResultDTO;
import com.anchr.core.settings.interfaces.rest.dto.SearchSettingDTO;
import com.anchr.core.settings.interfaces.rest.dto.SearchSettingUpdateRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Settings APIs for capabilities, providers, search parameters, and preferences.
 */
@Validated
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsApiController {

    private static final List<String> REQUIRES_REINDEX_FIELDS = List.of(
            "embeddingModel", "embeddingDimension", "chunkSize", "chunkOverlap");

    private final SettingsQueryService settingsQueryService;
    private final SearchSettingService searchSettingService;
    private final PreferenceSettingService preferenceSettingService;
    private final ProviderSettingService providerSettingService;

    @RequireAuth
    @GetMapping("/capabilities")
    public Result<CapabilitiesDTO> capabilities() {
        return Result.success(settingsQueryService.capabilities());
    }

    @RequireAuth
    @GetMapping("/providers")
    public Result<ProviderListDTO> providers() {
        return Result.success(settingsQueryService.providers());
    }

    @RequireAuth
    @GetMapping("/search")
    public Result<SearchSettingDTO> getSearch() {
        return Result.success(toSearchDto(searchSettingService.get(), List.of()));
    }

    @RequireAuth
    @PatchMapping("/search")
    public Result<SearchSettingDTO> updateSearch(@Valid @RequestBody SearchSettingUpdateRequestDTO request) {
        SearchSetting setting = searchSettingService.update(
                request.getTopK(), request.getRerankWindow(), request.getRrfK(), request.getMinScore());
        return Result.success(toSearchDto(setting, List.of("Search settings take effect immediately.")));
    }

    @RequireAuth
    @GetMapping("/preferences")
    public Result<PreferenceDTO> getPreferences() {
        return Result.success(PreferenceDTO.builder()
                .theme(preferenceSettingService.get().getTheme().name())
                .build());
    }

    @RequireAuth
    @PatchMapping("/preferences")
    public Result<PreferenceDTO> updatePreferences(@Valid @RequestBody PreferenceUpdateRequestDTO request) {
        return Result.success(PreferenceDTO.builder()
                .theme(preferenceSettingService.update(PreferenceTheme.parse(request.getTheme())).getTheme().name())
                .build());
    }

    @RequireAuth
    @PatchMapping("/providers/selection")
    public Result<ProviderSwitchResultDTO> switchProvider(@Valid @RequestBody ProviderSwitchRequestDTO request) {
        ProviderSwitchResult result = providerSettingService.switchProvider(
                ProviderType.parse(request.getProviderType()), request.getProviderName());
        return Result.success(ProviderSwitchResultDTO.builder()
                .providerType(result.getProviderType().name())
                .providerName(result.getProviderName())
                .version(result.getVersion())
                .effectiveImmediately(result.isEffectiveImmediately())
                .warnings(result.getWarnings())
                .build());
    }

    private SearchSettingDTO toSearchDto(SearchSetting setting, List<String> warnings) {
        return SearchSettingDTO.builder()
                .topK(setting.getTopK())
                .rerankWindow(setting.getRerankWindow())
                .rrfK(setting.getRrfK())
                .minScore(setting.getMinScore())
                .hotUpdateSupported(setting.isHotUpdateSupported())
                .requiresReindexFields(REQUIRES_REINDEX_FIELDS)
                .warnings(warnings)
                .build();
    }
}
