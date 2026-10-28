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

    /**
     * @param userScope folder name the credential is confined to. Scoping the session policy is
     *                  what stops one demo visitor from writing over another's uploads, or over
     *                  the seeded content, in the shared bucket.
     */
    public AuthStorageCredential issueUploadCredential(String userScope) {
        StorageTemporaryCredential credential =
                storageRuntimeApi.issueTemporaryCredentialFor(userScope);
        return toCredential(credential);
    }

    /** Unscoped variant, kept for callers that have no user identity to scope by. */
    public AuthStorageCredential issueUploadCredential() {
        return toCredential(storageRuntimeApi.issueTemporaryCredential());
    }

    private AuthStorageCredential toCredential(StorageTemporaryCredential credential) {
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
