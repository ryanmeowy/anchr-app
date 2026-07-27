package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactPaths;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Secret-free, stable portion of a Docling v2 request.
 *
 * <p>The signed source URL and temporary OSS credentials are deliberately excluded. They are
 * refreshed for every submission, while this snapshot keeps the fields used by Docling's v2
 * idempotency fingerprint stable across process restarts and lost-job resubmissions.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestionParseRequestSnapshot(
        int artifactVersion,
        int contractVersion,
        String fileName,
        ParseRequest.Options options,
        StableOssTarget ossTarget
) {

    static final int CURRENT_VERSION = 1;
    static final int LEGACY_DOCLING_CONTRACT_VERSION = 2;
    static final int EMBEDDED_IMAGE_CONTRACT_VERSION = 3;

    static IngestionParseRequestSnapshot capture(Asset asset,
                                                 boolean includeEmbeddedImages,
                                                 StorageConfig storageConfig,
                                                 String taskId,
                                                 String itemId,
                                                 int parseAttempt) {
        if (asset == null || !StringUtils.hasText(asset.getFileName())) {
            throw new IllegalArgumentException("Asset file name is required for a Docling request.");
        }
        StableOssTarget target = null;
        if (includeEmbeddedImages && storageConfig != null) {
            target = new StableOssTarget(
                    requireText(storageConfig.getEndpoint(), "OSS endpoint"),
                    requireText(storageConfig.getBucket(), "OSS bucket"),
                    IngestionArtifactPaths.imagePrefix(
                            storageConfig.getPrefix(), taskId, itemId, parseAttempt),
                    IngestionArtifactPaths.ATTEMPT_PREFIX_LAYOUT);
        }
        return new IngestionParseRequestSnapshot(
                CURRENT_VERSION,
                includeEmbeddedImages
                        ? EMBEDDED_IMAGE_CONTRACT_VERSION
                        : LEGACY_DOCLING_CONTRACT_VERSION,
                asset.getFileName(),
                ParseRequest.Options.chunkModel(includeEmbeddedImages),
                target);
    }

    IngestionParseRequestSnapshot validated() {
        if (artifactVersion != CURRENT_VERSION) {
            throw new IllegalStateException("Unsupported ingestion parse request snapshot version.");
        }
        if (contractVersion != LEGACY_DOCLING_CONTRACT_VERSION
                && contractVersion != EMBEDDED_IMAGE_CONTRACT_VERSION) {
            throw new IllegalStateException("Unsupported Docling contract version in parse request snapshot.");
        }
        requireText(fileName, "Docling file name");
        if (options == null) {
            throw new IllegalStateException("Docling parse options are missing from the request snapshot.");
        }
        if (ossTarget != null) {
            if (contractVersion != EMBEDDED_IMAGE_CONTRACT_VERSION) {
                throw new IllegalStateException(
                        "Embedded-image output requires Docling contract version 3.");
            }
            ossTarget.validated();
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
                        "Temporary OSS credentials are required by the persisted parse request snapshot.");
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

    boolean targets(StorageConfig storageConfig,
                    String taskId,
                    String itemId,
                    int parseAttempt) {
        if (ossTarget == null) {
            return true;
        }
        if (storageConfig == null
                || !ossTarget.endpoint().equals(storageConfig.getEndpoint())
                || !ossTarget.bucket().equals(storageConfig.getBucket())) {
            return false;
        }
        if (ossTarget.objectKeyLayout() == null) {
            return ossTarget.basePath().equals(
                    storageConfig.getPrefix() == null ? "" : storageConfig.getPrefix());
        }
        return IngestionArtifactPaths.ATTEMPT_PREFIX_LAYOUT.equals(
                ossTarget.objectKeyLayout())
                && ossTarget.basePath().equals(IngestionArtifactPaths.imagePrefix(
                        storageConfig.getPrefix(), taskId, itemId, parseAttempt));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StableOssTarget(String endpoint, String bucket, String basePath,
                                  String objectKeyLayout) {

        private StableOssTarget validated() {
            requireText(endpoint, "OSS endpoint");
            requireText(bucket, "OSS bucket");
            if (basePath == null) {
                throw new IllegalStateException("OSS base path is missing from the request snapshot.");
            }
            if (objectKeyLayout != null
                    && !IngestionArtifactPaths.ATTEMPT_PREFIX_LAYOUT.equals(objectKeyLayout)) {
                throw new IllegalStateException("Unsupported embedded-image object key layout.");
            }
            return this;
        }
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(fieldName + " must not be blank.");
        }
        return value;
    }
}
