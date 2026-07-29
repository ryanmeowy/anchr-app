package com.anchr.core.settings.application.api.model;

/** Immutable temporary object-storage credential returned by Capability. */
public record StorageTemporaryCredential(
        String endpoint,
        String bucket,
        String region,
        String prefix,
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        String expiration
) {
}
