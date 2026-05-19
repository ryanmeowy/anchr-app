package com.smart.vision.core.integration.storage.service.aliyun.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static com.smart.vision.core.integration.constant.AliyunConstant.X_OSS_PROCESS_EMBEDDING;

/**
 * Aliyun object storage configuration.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
public class AliyunObjectStorageConfig {

    private String endpoint;
    private String publicEndpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;
    private String roleArn;
    private String imageProcessEmbedding = X_OSS_PROCESS_EMBEDDING;
}
