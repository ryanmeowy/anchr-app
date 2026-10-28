package com.anchr.core.integration.storage;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.sts.AssumeRoleRequest;
import com.aliyuncs.auth.sts.AssumeRoleResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.anchr.core.settings.domain.model.StorageConfig;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Issues temporary STS tokens for frontend OSS uploads.
 */
@Service
public class StorageTokenIssuer {

    private static final long STS_DURATION_SECONDS = 3600L;

    public Map<String, Object> issueToken(StorageConfig config, String accessKey, String secretKey) {
        return issueToken(config, accessKey, secretKey, null);
    }

    /**
     * @param inlinePolicy session policy to attach, or null for the role's full permissions.
     *                     Without one, the credential can reach every object in the bucket, so any
     *                     caller that knows which folder it owns should pass a policy.
     */
    public Map<String, Object> issueToken(StorageConfig config, String accessKey, String secretKey,
                                          String inlinePolicy) {
        DefaultProfile profile = DefaultProfile.getProfile(config.getRegion(), accessKey, secretKey);
        IAcsClient client = new DefaultAcsClient(profile);
        AssumeRoleRequest request = new AssumeRoleRequest();
        request.setRoleArn(config.getRoleArn());
        request.setRoleSessionName("anchr-upload");
        request.setDurationSeconds(STS_DURATION_SECONDS);
        if (inlinePolicy != null && !inlinePolicy.isBlank()) {
            request.setPolicy(inlinePolicy);
        }

        try {
            AssumeRoleResponse response = client.getAcsResponse(request);
            return getObjectMap(config, response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to issue STS token: " + e.getMessage(), e);
        }
    }

    /**
     * Restricts writes to {@code prefix/scope/}. Read-back of the just-uploaded object is included
     * because the ingestion pipeline fetches it again; nothing outside the caller's folder is.
     */
    public static String uploadPolicy(String bucket, String prefix, String scope) {
        String base = (prefix == null ? "" : prefix) + scope + "/";
        String resource = "acs:oss:*:*:" + bucket + "/" + base + "*";
        return "{\"Version\":\"1\",\"Statement\":["
                + "{\"Effect\":\"Allow\","
                + "\"Action\":[\"oss:PutObject\",\"oss:AbortMultipartUpload\"],"
                + "\"Resource\":[\"" + resource + "\"]}]}";
    }

    private @NonNull Map<String, Object> getObjectMap(StorageConfig config, AssumeRoleResponse response) {
        Map<String, Object> result = new HashMap<>();
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
