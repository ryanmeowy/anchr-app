package com.anchr.core.kb.interfaces.rest;

import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.interfaces.rest.dto.AssetDTO;
import com.anchr.core.kb.interfaces.rest.dto.AssetListDTO;
import com.anchr.core.kb.interfaces.rest.dto.KbQueryRequestDTO;
import com.anchr.core.kb.interfaces.rest.dto.KnowledgeBaseCreateRequestDTO;
import com.anchr.core.kb.interfaces.rest.dto.KnowledgeBaseDTO;
import com.anchr.core.kb.interfaces.rest.dto.KnowledgeBaseListDTO;
import com.anchr.core.kb.interfaces.rest.dto.KnowledgeBaseStatsDTO;
import com.anchr.core.kb.interfaces.rest.dto.KnowledgeBaseUpdateRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Knowledge base product APIs.
 */
@Validated
@RestController
@RequestMapping("/api/v1/kbs")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @RequireAuth
    @PostMapping
    public Result<KnowledgeBaseDTO> create(@Valid @RequestBody KnowledgeBaseCreateRequestDTO request) {
        return Result.success(KnowledgeBaseDTO.from(
                knowledgeBaseService.create(request.getName(), request.getDescription())));
    }

    @RequireAuth
    @PostMapping("/search")
    public Result<KnowledgeBaseListDTO> listKbs(@RequestBody KbQueryRequestDTO request) {
        String status = parseStatus(request.getStatus());
        LocalDateTime after = parseDateTimeParam(request.getUpdateAfter());
        LocalDateTime before = parseDateTimeParam(request.getUpdateBefore());
        KnowledgeBaseService.PagedResult<KnowledgeBase> result =
                knowledgeBaseService.listKbs(trimToNull(request.getKeyword()), status, after, before, request.getPage(), request.getSize());
        return Result.success(KnowledgeBaseListDTO.builder()
                .items(result.items().stream().map(KnowledgeBaseDTO::from).toList())
                .total(result.total())
                .page(result.page())
                .size(result.size())
                .build());
    }

    @RequireAuth
    @GetMapping("/{kbId}")
    public Result<KnowledgeBaseDTO> get(@PathVariable @NotBlank String kbId) {
        return Result.success(KnowledgeBaseDTO.from(knowledgeBaseService.get(kbId)));
    }

    @RequireAuth
    @PatchMapping("/{kbId}")
    public Result<KnowledgeBaseDTO> update(@PathVariable @NotBlank String kbId,
                                           @Valid @RequestBody KnowledgeBaseUpdateRequestDTO request) {
        return Result.success(KnowledgeBaseDTO.from(
                knowledgeBaseService.update(kbId, request.getName(), request.getDescription())));
    }

    @RequireAuth
    @DeleteMapping("/{kbId}")
    public Result<Void> archive(@PathVariable @NotBlank String kbId) {
        knowledgeBaseService.archive(kbId);
        return Result.success();
    }

    @RequireAuth
    @GetMapping("/{kbId}/stats")
    public Result<KnowledgeBaseStatsDTO> stats(@PathVariable @NotBlank String kbId) {
        return Result.success(KnowledgeBaseStatsDTO.from(knowledgeBaseService.getStats(kbId)));
    }

    @RequireAuth
    @GetMapping("/{kbId}/documents")
    public Result<AssetListDTO> listDocuments(
            @PathVariable @NotBlank String kbId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        KnowledgeBaseService.PagedResult<Asset> result = knowledgeBaseService.listDocuments(kbId, page, size);
        return Result.success(AssetListDTO.builder()
                .items(result.items().stream().map(AssetDTO::from).toList())
                .total(result.total())
                .page(result.page())
                .size(result.size())
                .build());
    }

    @RequireAuth
    @GetMapping("/{kbId}/documents/{assetId}")
    public Result<AssetDTO> getDocument(@PathVariable @NotBlank String kbId,
                                                @PathVariable @NotBlank String assetId) {
        return Result.success(AssetDTO.from(knowledgeBaseService.getDocument(kbId, assetId)));
    }

    @RequireAuth
    @DeleteMapping("/{kbId}/documents/{assetId}")
    public Result<Void> deleteDocument(@PathVariable @NotBlank String kbId,
                                       @PathVariable @NotBlank String assetId) {
        knowledgeBaseService.deleteDocument(kbId, assetId);
        return Result.success();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String parseStatus(String status) {
        if (StringUtils.hasText(status)) {
            KnowledgeBaseStatus knowledgeBaseStatus = KnowledgeBaseStatus.fetchByCode(status);
            return null == knowledgeBaseStatus ? null : knowledgeBaseStatus.name();
        }
        return null;
    }

    private static LocalDateTime parseDateTimeParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.chars().allMatch(Character::isDigit)) {
                return LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(Long.parseLong(value)),
                        java.time.ZoneOffset.UTC);
            }
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
