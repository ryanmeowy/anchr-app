package com.anchr.core.auth.application.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Auth-owned projection of a temporary upload credential. */
public record AuthStorageCredential(
        String endpoint,
        String bucket,
        String region,
        String prefix,
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        String expiration
) {

    public Map<String, Object> toResponseMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoint", endpoint);
        result.put("bucket", bucket);
        result.put("region", region);
        result.put("prefix", prefix);
        result.put("accessKeyId", accessKeyId);
        result.put("accessKeySecret", accessKeySecret);
        result.put("securityToken", securityToken);
        result.put("expiration", expiration);
        return result;
    }
}
