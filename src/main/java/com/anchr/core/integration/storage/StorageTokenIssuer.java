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
        return issueToken(config, accessKey, secretKey, null, null);
    }

    /** Issues a credential whose policy only permits writes under {@code scope}, and reports that
     *  folder as the prefix so the client uploads where it is allowed to. */
    public Map<String, Object> issueToken(StorageConfig config, String accessKey, String secretKey,
                                          String inlinePolicy, String userScope) {
        return issueTokenInternal(config, accessKey, secretKey, inlinePolicy, userScope);
    }

    /**
     * @param inlinePolicy session policy to attach, or null for the role's full permissions.
     *                     Without one, the credential can reach every object in the bucket, so any
     *                     caller that knows which folder it owns should pass a policy.
     */
    public Map<String, Object> issueToken(StorageConfig config, String accessKey, String secretKey,
                                          String inlinePolicy) {
        return issueTokenInternal(config, accessKey, secretKey, inlinePolicy, null);
    }

    private Map<String, Object> issueTokenInternal(StorageConfig config, String accessKey,
                                                   String secretKey, String inlinePolicy,
                                                   String userScope) {
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
            return getObjectMap(config, response, userScope);
        } catch (Exception e) {
            throw new RuntimeException("Failed to issue STS token: " + e.getMessage(), e);
        }
    }

    /**
     * Restricts writes to {@code prefix/scope/}.
     *
     * <p>Deliberately write-only: the browser uploads the object and the pipeline reads it back
     * through its own credentials, so a visitor's credential never needs read access. Anything
     * outside the caller's folder is denied.
     */
    public static String uploadPolicy(String bucket, String prefix, String scope) {
        // Ids are hexadecimal today, but this value is interpolated into a JSON document and an
        // OSS resource ARN: refuse anything that could break either, rather than trusting callers.
        if (scope == null || !scope.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("scope must be a plain identifier");
        }
        String base = (prefix == null ? "" : prefix) + scope + "/";
        String resource = "acs:oss:*:*:" + bucket + "/" + base + "*";
        return "{\"Version\":\"1\",\"Statement\":["
                + "{\"Effect\":\"Allow\","
                + "\"Action\":[\"oss:PutObject\",\"oss:AbortMultipartUpload\"],"
                + "\"Resource\":[\"" + resource + "\"]}]}";
    }

    private @NonNull Map<String, Object> getObjectMap(StorageConfig config, AssumeRoleResponse response,
                                                      String userScope) {
        Map<String, Object> result = new HashMap<>();
        result.put("endpoint", config.getEndpoint());
        result.put("bucket", config.getBucket());
        result.put("region", config.getRegion());
        // The session policy confines writes to <prefix><scope>/. Reporting the bare prefix would
        // tell the client to upload outside that folder, and every PUT would be denied.
        String basePrefix = config.getPrefix() != null ? config.getPrefix() : "";
        String effectivePrefix = userScope == null || userScope.isBlank()
                ? basePrefix
                : basePrefix + userScope + "/";
        result.put("prefix", effectivePrefix);
        result.put("accessKeyId", response.getCredentials().getAccessKeyId());
        result.put("accessKeySecret", response.getCredentials().getAccessKeySecret());
        result.put("securityToken", response.getCredentials().getSecurityToken());
        result.put("expiration", response.getCredentials().getExpiration());
        return result;
    }
}
