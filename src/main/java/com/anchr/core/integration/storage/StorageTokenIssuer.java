package com.anchr.core.integration.storage;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.sts.AssumeRoleRequest;
import com.aliyuncs.auth.sts.AssumeRoleResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.anchr.core.settings.domain.model.StorageConfig;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues temporary STS tokens for frontend OSS uploads.
 */
@Service
public class StorageTokenIssuer {

    private static final long STS_DURATION_SECONDS = 3600L;

    public Map<String, Object> issueToken(StorageConfig config, String accessKey, String secretKey) {
        DefaultProfile profile = DefaultProfile.getProfile(config.getRegion(), accessKey, secretKey);
        IAcsClient client = new DefaultAcsClient(profile);
        AssumeRoleRequest request = new AssumeRoleRequest();
        request.setRoleArn(config.getRoleArn());
        request.setRoleSessionName("anchr-upload");
        request.setDurationSeconds(STS_DURATION_SECONDS);

        try {
            AssumeRoleResponse response = client.getAcsResponse(request);
            return getObjectMap(config, response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to issue STS token: " + e.getMessage(), e);
        }
    }

    private static @NonNull Map<String, Object> getObjectMap(StorageConfig config, AssumeRoleResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoint", config.getEndpoint());
        result.put("bucket", config.getBucket());
        result.put("region", config.getRegion());
        result.put("prefix", config.getPrefix() != null ? config.getPrefix() : "");
        result.put("accessKeyId", response.getCredentials().getAccessKeyId());
        result.put("accessKeySecret", response.getCredentials().getAccessKeySecret());
        result.put("securityToken", response.getCredentials().getSecurityToken());
        result.put("expiration", response.getCredentials().getExpiration());
        return result;
    }
}
