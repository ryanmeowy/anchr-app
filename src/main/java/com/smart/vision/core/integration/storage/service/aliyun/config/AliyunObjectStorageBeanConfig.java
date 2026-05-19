package com.smart.vision.core.integration.storage.service.aliyun.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.comm.Protocol;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Aliyun object storage sdk bean configuration.
 */
@Configuration
@RequiredArgsConstructor
public class AliyunObjectStorageBeanConfig {

    private final AliyunObjectStorageConfig aliyunObjectStorageConfig;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "app.capability-provider", name = "object-storage", havingValue = "aliyun")
    public OSS ossClient() {
        ClientBuilderConfiguration conf = new ClientBuilderConfiguration();
        conf.setProtocol(Protocol.HTTPS);
        return new OSSClientBuilder().build(
                aliyunObjectStorageConfig.getEndpoint(),
                aliyunObjectStorageConfig.getAccessKeyId(),
                aliyunObjectStorageConfig.getAccessKeySecret(),
                conf
        );
    }
}

