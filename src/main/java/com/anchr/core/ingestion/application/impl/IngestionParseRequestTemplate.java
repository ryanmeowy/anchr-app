package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.ingestion.application.model.IngestionStorageTarget;
import com.anchr.core.kb.domain.model.Asset;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 单次整文档处理期间使用的 Docling 请求模板，仅存在于当前 worker 内存中。
 *
 * <p>签名下载地址和临时 OSS 凭据不进入模板，每次提交时即时生成；服务重启后不恢复
 * 当前处理，而是将对应 item 标记为失败。</p>
 */
public record IngestionParseRequestTemplate(
        int contractVersion,
        String fileName,
        ParseRequest.Options options,
        IngestionStorageTarget ossTarget
) {

    static final int LEGACY_DOCLING_CONTRACT_VERSION = 2;
    static final int EMBEDDED_IMAGE_CONTRACT_VERSION = 3;

    static IngestionParseRequestTemplate capture(Asset asset,
                                                 boolean includeEmbeddedImages,
                                                 IngestionStorageTarget storageTarget) {
        if (asset == null || !StringUtils.hasText(asset.getFileName())) {
            throw new IllegalArgumentException("Asset file name is required for a Docling request.");
        }
        IngestionStorageTarget target =
                includeEmbeddedImages ? storageTarget : null;
        return new IngestionParseRequestTemplate(
                includeEmbeddedImages
                        ? EMBEDDED_IMAGE_CONTRACT_VERSION
                        : LEGACY_DOCLING_CONTRACT_VERSION,
                asset.getFileName(),
                ParseRequest.Options.chunkModel(includeEmbeddedImages),
                target);
    }

    IngestionParseRequestTemplate validated() {
        if (contractVersion != LEGACY_DOCLING_CONTRACT_VERSION
                && contractVersion != EMBEDDED_IMAGE_CONTRACT_VERSION) {
            throw new IllegalStateException("Unsupported Docling contract version in parse request template.");
        }
        requireText(fileName, "Docling file name");
        if (options == null) {
            throw new IllegalStateException("Docling parse options are missing from the request template.");
        }
        if (ossTarget != null) {
            if (contractVersion != EMBEDDED_IMAGE_CONTRACT_VERSION) {
                throw new IllegalStateException(
                        "Embedded-image output requires Docling contract version 3.");
            }
            if (!IngestionImagePaths.DOCLING_OBJECT_KEY_LAYOUT.equals(
                    ossTarget.objectKeyLayout())) {
                throw new IllegalStateException(
                        "Unsupported embedded-image object key layout.");
            }
        }
        return this;
    }

    ParseRequest toRequest(String requestId,
                           String sourceRevision,
                           String sourceUrl,
                           Map<String, String> encryptedCredentials) {
        validated();
        ParseRequest.Oss oss = null;
        if (ossTarget != null) {
            if (encryptedCredentials == null || encryptedCredentials.isEmpty()) {
                throw new IllegalStateException(
                        "Temporary OSS credentials are required by the parse request template.");
            }
            oss = new ParseRequest.Oss(
                    ossTarget.endpoint(),
                    ossTarget.bucket(),
                    ossTarget.basePath(),
                    ossTarget.objectKeyLayout(),
                    Map.copyOf(encryptedCredentials));
        }
        return ParseRequest.builder()
                .requestId(requireText(requestId, "Docling request id"))
                .contractVersion(contractVersion)
                .sourceRevision(requireText(sourceRevision, "Docling source revision"))
                .sourceUrl(requireText(sourceUrl, "Docling source URL"))
                .fileName(fileName)
                .options(options)
                .oss(oss)
                .build();
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(fieldName + " must not be blank.");
        }
        return value;
    }
}
