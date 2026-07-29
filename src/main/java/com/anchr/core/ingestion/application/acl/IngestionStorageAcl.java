package com.anchr.core.ingestion.application.acl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.application.impl.IngestionImagePaths;
import com.anchr.core.ingestion.application.model.IngestionStorageCredential;
import com.anchr.core.ingestion.application.model.IngestionStorageTarget;
import com.anchr.core.settings.application.api.StorageRuntimeApi;
import com.anchr.core.settings.application.api.model.StorageLocationSnapshot;
import com.anchr.core.settings.application.api.model.StorageTemporaryCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Ingestion-side adapter for Capability-owned storage runtime facts and credentials. */
@Component
@RequiredArgsConstructor
public class IngestionStorageAcl {

    private final StorageRuntimeApi storageRuntimeApi;

    public Optional<IngestionStorageTarget> findTarget(
            String assetId,
            long targetGeneration
    ) {
        return storageRuntimeApi.findLocation()
                .map(location -> toTarget(location, assetId, targetGeneration));
    }

    public IngestionStorageCredential issueTemporaryCredential(
            IngestionStorageTarget expectedTarget,
            String assetId,
            long targetGeneration
    ) {
        StorageLocationSnapshot location = storageRuntimeApi.findLocation()
                .orElseThrow(() -> new BusinessException(
                        ApiError.INTERNAL_ERROR,
                        "Docling image output requires storage configuration."));
        verifyTarget(expectedTarget, toTarget(location, assetId, targetGeneration));
        StorageTemporaryCredential credential =
                storageRuntimeApi.issueTemporaryCredential();
        verifyCredentialTarget(
                expectedTarget, credential, assetId, targetGeneration);
        return new IngestionStorageCredential(
                credential.endpoint(),
                credential.bucket(),
                credential.region(),
                credential.prefix(),
                credential.accessKeyId(),
                credential.accessKeySecret(),
                credential.securityToken(),
                credential.expiration());
    }

    private IngestionStorageTarget toTarget(
            StorageLocationSnapshot location,
            String assetId,
            long targetGeneration
    ) {
        return new IngestionStorageTarget(
                location.endpoint(),
                location.bucket(),
                IngestionImagePaths.imagePrefix(
                        location.prefix(), assetId, targetGeneration),
                IngestionImagePaths.DOCLING_OBJECT_KEY_LAYOUT);
    }

    private void verifyCredentialTarget(
            IngestionStorageTarget expected,
            StorageTemporaryCredential credential,
            String assetId,
            long targetGeneration
    ) {
        IngestionStorageTarget actual = new IngestionStorageTarget(
                credential.endpoint(),
                credential.bucket(),
                IngestionImagePaths.imagePrefix(
                        credential.prefix(), assetId, targetGeneration),
                IngestionImagePaths.DOCLING_OBJECT_KEY_LAYOUT);
        verifyTarget(expected, actual);
    }

    private void verifyTarget(
            IngestionStorageTarget expected,
            IngestionStorageTarget actual
    ) {
        if (expected == null || !expected.equals(actual)) {
            throw new BusinessException(
                    ApiError.INTERNAL_ERROR,
                    "Storage output target changed during document processing.");
        }
    }
}
