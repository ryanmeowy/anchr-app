package com.anchr.core.settings.application;

import com.anchr.core.auth.infrastructure.AesUtil;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Application service for object storage configuration.
 */
@Service
@RequiredArgsConstructor
public class StorageConfigService {


    private final StorageConfigRepository repository;
    private final AesUtil aesUtil;
    private final IdGen idGen;

    public Optional<StorageConfig> get() {
        return repository.find();
    }

    public StorageConfig save(StorageConfigUpdateRequest request) {
        StorageConfig existing = repository.find().orElse(null);
        String akEnc, skEnc;

        if (StringUtils.hasText(request.accessKey())) {
            akEnc = aesUtil.encrypt(request.accessKey());
        } else if (existing != null) {
            akEnc = existing.getAccessKeyEnc();
        } else {
            throw new IllegalArgumentException("accessKey is required for new configuration.");
        }

        if (StringUtils.hasText(request.secretKey())) {
            skEnc = aesUtil.encrypt(request.secretKey());
        } else if (existing != null) {
            skEnc = existing.getSecretKeyEnc();
        } else {
            throw new IllegalArgumentException("secretKey is required for new configuration.");
        }

        StorageConfig config = StorageConfig.builder()
                .id(existing != null ? existing.getId() : idGen.nextIdStr())
                .endpoint(request.endpoint())
                .accessKeyEnc(akEnc)
                .secretKeyEnc(skEnc)
                .bucket(request.bucket())
                .region(request.region())
                .prefix(request.prefix())
                .roleArn(request.roleArn())
                .enabled(true)
                .updatedBy(UserContextHolder.get().userId())
                .updatedAt(LocalDateTime.now())
                .build();

        return repository.upsert(config);
    }

    public record StorageConfigUpdateRequest(String endpoint, String accessKey, String secretKey,
                                              String bucket, String region, String prefix, String roleArn) {}
}
