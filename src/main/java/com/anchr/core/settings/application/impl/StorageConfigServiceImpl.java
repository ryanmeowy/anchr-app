package com.anchr.core.settings.application.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.settings.application.StorageConfigService;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import com.anchr.core.settings.interfaces.rest.dto.StorageConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.StorageConnectionTestResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Default implementation for storage configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageConfigServiceImpl implements StorageConfigService {

    private final StorageConfigRepository repository;
    private final AesUtil aesUtil;
    private final IdGen idGen;

    @Override
    public Optional<StorageConfig> get() {
        return repository.find();
    }

    @Override
    public StorageConfig save(StorageConfigUpdateRequestDTO request) {
        StorageConfig existing = repository.find().orElse(null);
        String akEnc, skEnc;

        if (StringUtils.hasText(request.getAccessKey())) {
            akEnc = aesUtil.encrypt(request.getAccessKey());
        } else if (existing != null) {
            akEnc = existing.getAccessKeyEnc();
        } else {
            throw new IllegalArgumentException("accessKey is required for new configuration.");
        }

        if (StringUtils.hasText(request.getSecretKey())) {
            skEnc = aesUtil.encrypt(request.getSecretKey());
        } else if (existing != null) {
            skEnc = existing.getSecretKeyEnc();
        } else {
            throw new IllegalArgumentException("secretKey is required for new configuration.");
        }

        StorageConfig config = StorageConfig.builder()
                .id(existing != null ? existing.getId() : idGen.nextId())
                .endpoint(request.getEndpoint())
                .accessKeyEnc(akEnc)
                .secretKeyEnc(skEnc)
                .bucket(request.getBucket())
                .region(request.getRegion())
                .prefix(request.getPrefix())
                .roleArn(request.getRoleArn())
                .enabled(true)
                .updatedBy(UserContextHolder.get().userId())
                .updatedAt(LocalDateTime.now())
                .build();

        return repository.upsert(config);
    }

    @Override
    public StorageConnectionTestResultDTO test(StorageConnectionTestRequestDTO request) {
        long start = System.currentTimeMillis();
        OSS client = null;
        try {
            client = new OSSClientBuilder().build(
                    request.getEndpoint(), request.getAccessKey(), request.getSecretKey());
            boolean exists = client.doesBucketExist(request.getBucket());
            long latencyMs = System.currentTimeMillis() - start;
            return StorageConnectionTestResultDTO.builder()
                    .success(exists)
                    .latencyMs(latencyMs)
                    .message(exists ? "Bucket exists, connection OK." : "Bucket not found.")
                    .build();
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("Storage connection test failed: {}", e.getMessage());
            return StorageConnectionTestResultDTO.builder()
                    .success(false)
                    .latencyMs(latencyMs)
                    .message(e.getMessage())
                    .build();
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    @Override
    public String maskAccessKey(StorageConfig config) {
        return maskCredential(config.getAccessKeyEnc());
    }

    @Override
    public String maskSecretKey(StorageConfig config) {
        return maskCredential(config.getSecretKeyEnc());
    }

    private String maskCredential(String encrypted) {
        try {
            String decrypted = aesUtil.decrypt(encrypted);
            if (decrypted.length() <= 8) {
                return "****";
            }
            return decrypted.substring(0, 4) + "****" + decrypted.substring(decrypted.length() - 4);
        } catch (Exception e) {
            return "****";
        }
    }

}
