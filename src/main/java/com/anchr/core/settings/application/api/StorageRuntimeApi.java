package com.anchr.core.settings.application.api;

import com.anchr.core.settings.application.api.model.StorageLocationSnapshot;
import com.anchr.core.settings.application.api.model.StorageTemporaryCredential;

import java.util.Optional;

/** Runtime object-storage capability exposed without leaking persisted credentials. */
public interface StorageRuntimeApi {

    Optional<StorageLocationSnapshot> findLocation();

    StorageTemporaryCredential issueTemporaryCredential();

    /**
     * Issues a credential scoped to one caller's own folder.
     *
     * <p>The unscoped variant hands out the role's full permissions for an hour, which in the
     * shared demo bucket means any visitor can read or overwrite anything. Defaulting to the
     * unscoped behaviour keeps existing callers and mocks compiling; implementations that can
     * scope should override this.
     */
    default StorageTemporaryCredential issueTemporaryCredentialFor(String userScope) {
        return issueTemporaryCredential();
    }
}
