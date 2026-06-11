package com.anchr.core.settings.interfaces.rest;

import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.settings.application.CapabilityConfigService;
import com.anchr.core.settings.application.PreferenceSettingService;
import com.anchr.core.settings.application.SearchSettingService;
import com.anchr.core.settings.application.model.SearchSetting;
import com.anchr.core.settings.domain.model.PreferenceTheme;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestResultDTO;
import com.anchr.core.settings.interfaces.rest.dto.PreferenceDTO;
import com.anchr.core.settings.interfaces.rest.dto.PreferenceUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.SearchSettingDTO;
import com.anchr.core.settings.interfaces.rest.dto.SearchSettingUpdateRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Settings APIs.
 */
@Validated
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsApiController {

    private static final List<String> REQUIRES_REINDEX_FIELDS = List.of(
            "embeddingModel", "embeddingDimension", "chunkSize", "chunkOverlap");

    private final SearchSettingService searchSettingService;
    private final PreferenceSettingService preferenceSettingService;
    private final CapabilityConfigService capabilityConfigService;

    // ── embedding config ──────────────────────────────────────────────────

    @RequireAuth
    @GetMapping("/embedding")
    public Result<CapabilityConfigDTO> getEmbeddingConfig() {
        return capabilityConfigService.get(CapabilityConfigService.CAPABILITY_EMBEDDING)
                .map(Result::success)
                .orElse(Result.success(null));
    }

    @RequireAuth
    @PatchMapping("/embedding")
    public Result<CapabilityConfigDTO> updateEmbeddingConfig(
            @Valid @RequestBody CapabilityConfigUpdateRequestDTO request) {
        return Result.success(capabilityConfigService.save(
                CapabilityConfigService.CAPABILITY_EMBEDDING, request));
    }

    @RequireAuth
    @PostMapping("/embedding/test")
    public Result<CapabilityConnectionTestResultDTO> testEmbeddingConnection(
            @Valid @RequestBody CapabilityConnectionTestRequestDTO request) {
        return Result.success(capabilityConfigService.test(request));
    }

    // ── search ────────────────────────────────────────────────────────────

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

    // ── preferences ───────────────────────────────────────────────────────

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
