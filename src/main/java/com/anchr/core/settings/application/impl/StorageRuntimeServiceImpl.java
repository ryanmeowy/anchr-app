package com.anchr.core.settings.application.impl;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.integration.storage.StorageTokenIssuer;
import com.anchr.core.settings.application.api.StorageRuntimeApi;
import com.anchr.core.settings.application.api.model.StorageLocationSnapshot;
import com.anchr.core.settings.application.api.model.StorageTemporaryCredential;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Capability-owned runtime access to the active storage configuration. */
@Service
@RequiredArgsConstructor
public class StorageRuntimeServiceImpl implements StorageRuntimeApi {

    private final StorageConfigRepository repository;
    private final AesUtil aesUtil;
    private final StorageTokenIssuer tokenIssuer;

    @Override
    public Optional<StorageLocationSnapshot> findLocation() {
        return repository.find().map(this::toLocation);
    }

    @Override
    public StorageTemporaryCredential issueTemporaryCredential() {
        StorageConfig config = repository.find()
                .orElseThrow(() -> new RuntimeException("Object storage is not configured."));
        String accessKey = aesUtil.decrypt(config.getAccessKeyEnc());
        String secretKey = aesUtil.decrypt(config.getSecretKeyEnc());
        Map<String, Object> token = tokenIssuer.issueToken(config, accessKey, secretKey);
        return new StorageTemporaryCredential(
                text(token, "endpoint"),
                text(token, "bucket"),
                text(token, "region"),
                text(token, "prefix"),
                text(token, "accessKeyId"),
                text(token, "accessKeySecret"),
                text(token, "securityToken"),
                text(token, "expiration"));
    }

    private StorageLocationSnapshot toLocation(StorageConfig config) {
        return new StorageLocationSnapshot(
                config.getEndpoint(),
                config.getBucket(),
                config.getRegion(),
                config.getPrefix() == null ? "" : config.getPrefix());
    }

    private String text(Map<String, Object> values, String key) {
        return Objects.toString(values.get(key), "");
    }
}
