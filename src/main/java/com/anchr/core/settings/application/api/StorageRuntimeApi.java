package com.anchr.core.settings.application.api;

import com.anchr.core.settings.application.api.model.StorageLocationSnapshot;
import com.anchr.core.settings.application.api.model.StorageTemporaryCredential;

import java.util.Optional;

/** Runtime object-storage capability exposed without leaking persisted credentials. */
public interface StorageRuntimeApi {

    Optional<StorageLocationSnapshot> findLocation();

    StorageTemporaryCredential issueTemporaryCredential();
}
