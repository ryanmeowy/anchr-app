package com.anchr.core.kb.application.acl;

import com.anchr.core.settings.application.api.StorageRuntimeApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Knowledge-side adapter for Capability-owned storage location facts. */
@Component
@RequiredArgsConstructor
public class KnowledgeStorageAcl {

    private final StorageRuntimeApi storageRuntimeApi;

    public Optional<String> findConfiguredPrefix() {
        return storageRuntimeApi.findLocation().map(location -> location.prefix() == null
                ? "" : location.prefix());
    }
}
