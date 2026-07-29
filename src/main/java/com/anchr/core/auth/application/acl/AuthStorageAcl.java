package com.anchr.core.auth.application.acl;

import com.anchr.core.auth.application.model.AuthStorageCredential;
import com.anchr.core.settings.application.api.StorageRuntimeApi;
import com.anchr.core.settings.application.api.model.StorageTemporaryCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Auth-side adapter for Capability-owned temporary storage credentials. */
@Component
@RequiredArgsConstructor
public class AuthStorageAcl {

    private final StorageRuntimeApi storageRuntimeApi;

    public void requireConfigured() {
        storageRuntimeApi.findLocation()
                .orElseThrow(() -> new RuntimeException("Object storage is not configured."));
    }

    public AuthStorageCredential issueUploadCredential() {
        StorageTemporaryCredential credential =
                storageRuntimeApi.issueTemporaryCredential();
        return new AuthStorageCredential(
                credential.endpoint(),
                credential.bucket(),
                credential.region(),
                credential.prefix(),
                credential.accessKeyId(),
                credential.accessKeySecret(),
                credential.securityToken(),
                credential.expiration());
    }
}
