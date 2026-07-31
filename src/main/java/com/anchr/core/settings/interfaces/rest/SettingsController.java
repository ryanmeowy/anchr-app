package com.anchr.core.settings.interfaces.rest;

import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.settings.domain.model.EmbedParamEnum;
import com.anchr.core.settings.domain.model.GenParamEnum;
import com.anchr.core.settings.domain.model.RerankParamEnum;
import com.anchr.core.settings.application.CapabilityConfigService;
import com.anchr.core.settings.application.RuntimeConfigService;
import com.anchr.core.settings.application.StorageConfigService;
import com.anchr.core.settings.domain.model.ModelTypeEnum;
import com.anchr.core.settings.interfaces.rest.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Settings APIs for capability configuration.
 */
@Validated
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final CapabilityConfigService capabilityConfigService;
    private final StorageConfigService storageConfigService;
    private final RuntimeConfigService runtimeConfigService;

    // ── runtime config ─────────────────────────────────────────────────────

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/runtime")
    public Result<RuntimeConfigResponseDTO> getRuntimeConfig() {
        return Result.success(runtimeConfigService.getAll());
    }

    @RequireAuth
    @PutMapping("/runtime")
    public Result<RuntimeConfigGroupDTO> updateRuntimeConfig(
            @Valid @RequestBody RuntimeConfigUpdateRequestDTO request) {
        return Result.success(runtimeConfigService.update(request));
    }

    // ── capability config ──────────────────────────────────────────────────

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/{capability}")
    public Result<List<CapabilityConfigDTO>> getConfig(@PathVariable String capability) {
        List<CapabilityConfigDTO> configs = capabilityConfigService.get(capability.toUpperCase());
        if ("GUEST".equals(UserContextHolder.get().role())) {
            configs = configs.stream()
                    .map(c -> CapabilityConfigDTO.builder()
                            .modelName(c.getModelName())
                            .enabled(c.isEnabled())
                            .id(c.getId())
                            .build())
                    .toList();
        }
        return Result.success(configs);
    }

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/{capability}/all")
    public Result<List<CapabilityConfigDTO>> getAllConfigs(@PathVariable String capability) {
        List<CapabilityConfigDTO> configs = capabilityConfigService.findAll(capability.toUpperCase());
        if ("GUEST".equals(UserContextHolder.get().role())) {
            configs = configs.stream()
                    .map(c -> CapabilityConfigDTO.builder()
                            .modelName(c.getModelName())
                            .enabled(c.isEnabled())
                            .id(c.getId())
                            .build())
                    .toList();
        }
        return Result.success(configs);
    }

    @RequireAuth
    @PostMapping("/{capability}")
    public Result<CapabilityConfigDTO> createConfig(
            @PathVariable String capability,
            @Valid @RequestBody CapabilityConfigUpdateRequestDTO request) {
        return Result.success(capabilityConfigService.create(capability.toUpperCase(), request));
    }

    @RequireAuth
    @PatchMapping("/{capability}/{id}")
    public Result<CapabilityConfigDTO> updateConfig(
            @PathVariable String capability,
            @PathVariable Long id,
            @Valid @RequestBody CapabilityConfigUpdateRequestDTO request) {
        return Result.success(capabilityConfigService.update(capability.toUpperCase(), id, request));
    }

    @RequireAuth
    @PutMapping("/{capability}/{id}/select")
    public Result<Void> selectConfig(@PathVariable String capability, @PathVariable Long id) {
        capabilityConfigService.select(capability.toUpperCase(), id);
        return Result.success(null);
    }

    @RequireAuth
    @DeleteMapping("/{capability}/{id}")
    public Result<Void> deleteConfig(@PathVariable String capability, @PathVariable Long id) {
        capabilityConfigService.del(capability.toUpperCase(), id);
        return Result.success(null);
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @GetMapping("/{capability}/params")
    public Result<CapabilityParamsDTO> params(@PathVariable String capability) {
        return Result.success(CapabilityParamsDTO.builder()
                .params(toParamItems(capability.toUpperCase()))
                .build());
    }

    private List<CapabilityParamsDTO.ParamItem> toParamItems(String capability) {
        ModelTypeEnum type = ModelTypeEnum.valueOf(capability.toUpperCase());
        return switch (type) {
            case ModelTypeEnum.GENERATION ->
                GenParamEnum.all().stream().map(p -> item(p.getKey(), p.getLabel())).toList();
            case ModelTypeEnum.RERANK ->
                RerankParamEnum.all().stream().map(p -> item(p.getKey(), p.getLabel())).toList();
            default ->
                EmbedParamEnum.all().stream().map(p -> item(p.getKey(), p.getLabel())).toList();
        };
    }

    private static CapabilityParamsDTO.ParamItem item(String key, String label) {
        return CapabilityParamsDTO.ParamItem.builder().key(key).label(label).build();
    }

    // ── test connection ────────────────────────────────────────────────────

    @RequireAuth(roles = {"ADMIN", "USER"})
    @PostMapping("/test-connection")
    public Result<CapabilityConnectionTestResultDTO> testConnection(
            @Valid @RequestBody CapabilityConnectionTestRequestDTO request) {
        return Result.success(capabilityConfigService.test(request));
    }

    // ── storage ────────────────────────────────────────────────────────────

    @RequireAuth(roles = {"ADMIN", "GUEST", "USER"})
    @GetMapping("/storage")
    public Result<StorageConfigDTO> getStorageConfig() {
        StorageConfigDTO storageConfigDTO = storageConfigService.get()
                .map(config -> StorageConfigDTO.from(config,
                        storageConfigService.maskAccessKey(config),
                        storageConfigService.maskSecretKey(config)))
                .orElse(null);
        if ("GUEST".equals(UserContextHolder.get().role()) && null != storageConfigDTO) {
            return Result.success(StorageConfigDTO.builder().enabled(storageConfigDTO.isEnabled()).build());
        }
        return Result.success(storageConfigDTO);
    }

    @RequireAuth
    @PatchMapping("/storage")
    public Result<StorageConfigDTO> updateStorageConfig(
            @Valid @RequestBody StorageConfigUpdateRequestDTO request) {
        var saved = storageConfigService.save(request);
        return Result.success(StorageConfigDTO.from(saved,
                storageConfigService.maskAccessKey(saved),
                storageConfigService.maskSecretKey(saved)));
    }

    @RequireAuth(roles = {"ADMIN", "USER"})
    @PostMapping("/storage/test")
    public Result<StorageConnectionTestResultDTO> testStorage(
            @Valid @RequestBody StorageConnectionTestRequestDTO request) {
        return Result.success(storageConfigService.test(request));
    }

    @RequireAuth
    @DeleteMapping("/storage/{id}")
    public Result<Void> deleteStorage(@PathVariable Long id) {
        storageConfigService.archive(id);
        return Result.success();
    }
}
