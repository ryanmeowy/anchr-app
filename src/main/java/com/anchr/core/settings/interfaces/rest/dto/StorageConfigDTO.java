package com.anchr.core.settings.interfaces.rest.dto;

import com.anchr.core.settings.domain.model.StorageConfig;
import lombok.Builder;
import lombok.Value;

/**
 * Storage config response — accessKey and secretKey are masked.
 */
@Value
@Builder
public class StorageConfigDTO {

    Long id;
    String endpoint;
    String bucket;
    String region;
    String prefix;
    String roleArn;
    String accessKeyMasked;
    String secretKeyMasked;
    boolean enabled;

    public static StorageConfigDTO from(StorageConfig config, String accessKeyMasked, String secretKeyMasked) {
        return StorageConfigDTO.builder()
                .id(config.getId())
                .endpoint(config.getEndpoint())
                .bucket(config.getBucket())
                .region(config.getRegion())
                .prefix(config.getPrefix())
                .roleArn(config.getRoleArn())
                .accessKeyMasked(accessKeyMasked)
                .secretKeyMasked(secretKeyMasked)
                .enabled(config.isEnabled())
                .build();
    }
}
