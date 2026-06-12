package com.anchr.core.settings.interfaces.rest;

import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.integration.ai.EmbedParamEnum;
import com.anchr.core.integration.ai.GenParamEnum;
import com.anchr.core.integration.ai.RerankParamEnum;
import com.anchr.core.settings.application.CapabilityConfigService;
import com.anchr.core.settings.application.StorageConfigService;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestResultDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityParamsDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConnectionTestResultDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    // ── embedding ────────────────────────────────────────────────────────

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

    @GetMapping("/embedding/params")
    public Result<CapabilityParamsDTO> embeddingParams() {
        return Result.success(CapabilityParamsDTO.builder()
                .params(EmbedParamEnum.all().stream()
                        .map(p -> CapabilityParamsDTO.ParamItem.builder()
                                .key(p.getKey()).label(p.getLabel()).build())
                        .toList())
                .build());
    }

    // ── generation ───────────────────────────────────────────────────────

    @RequireAuth
    @GetMapping("/generation")
    public Result<CapabilityConfigDTO> getGenerationConfig() {
        return capabilityConfigService.get(CapabilityConfigService.CAPABILITY_GENERATION)
                .map(Result::success)
                .orElse(Result.success(null));
    }

    @RequireAuth
    @PatchMapping("/generation")
    public Result<CapabilityConfigDTO> updateGenerationConfig(
            @Valid @RequestBody CapabilityConfigUpdateRequestDTO request) {
        return Result.success(capabilityConfigService.save(
                CapabilityConfigService.CAPABILITY_GENERATION, request));
    }

    @GetMapping("/generation/params")
    public Result<CapabilityParamsDTO> generationParams() {
        return Result.success(CapabilityParamsDTO.builder()
                .params(GenParamEnum.all().stream()
                        .map(p -> CapabilityParamsDTO.ParamItem.builder()
                                .key(p.getKey()).label(p.getLabel()).build())
                        .toList())
                .build());
    }

    // ── rerank ───────────────────────────────────────────────────────────

    @RequireAuth
    @GetMapping("/rerank")
    public Result<CapabilityConfigDTO> getRerankConfig() {
        return capabilityConfigService.get(CapabilityConfigService.CAPABILITY_RERANK)
                .map(Result::success)
                .orElse(Result.success(null));
    }

    @RequireAuth
    @PatchMapping("/rerank")
    public Result<CapabilityConfigDTO> updateRerankConfig(
            @Valid @RequestBody CapabilityConfigUpdateRequestDTO request) {
        return Result.success(capabilityConfigService.save(
                CapabilityConfigService.CAPABILITY_RERANK, request));
    }

    @GetMapping("/rerank/params")
    public Result<CapabilityParamsDTO> rerankParams() {
        return Result.success(CapabilityParamsDTO.builder()
                .params(RerankParamEnum.all().stream()
                        .map(p -> CapabilityParamsDTO.ParamItem.builder()
                                .key(p.getKey()).label(p.getLabel()).build())
                        .toList())
                .build());
    }

    // ── test connection ──────────────────────────────────────────────────

    @RequireAuth
    @PostMapping("/test-connection")
    public Result<CapabilityConnectionTestResultDTO> testConnection(
            @Valid @RequestBody CapabilityConnectionTestRequestDTO request) {
        return Result.success(capabilityConfigService.test(request));
    }

    // ── storage ────────────────────────────────────────────────────────────

    @RequireAuth
    @GetMapping("/storage")
    public Result<StorageConfigDTO> getStorageConfig() {
        return storageConfigService.get()
                .map(config -> StorageConfigDTO.from(config,
                        storageConfigService.maskAccessKey(config),
                        storageConfigService.maskSecretKey(config)))
                .map(Result::success)
                .orElse(Result.success(null));
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

    @RequireAuth
    @PostMapping("/storage/test")
    public Result<StorageConnectionTestResultDTO> testStorage(
            @Valid @RequestBody StorageConnectionTestRequestDTO request) {
        return Result.success(storageConfigService.test(request));
    }
}
