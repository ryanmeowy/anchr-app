package com.anchr.core.integration.storage.service.aliyun;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.sts.AssumeRoleRequest;
import com.aliyuncs.auth.sts.AssumeRoleResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.anchr.core.auth.domain.port.CredentialIssuePort;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.integration.storage.service.aliyun.config.AliyunObjectStorageConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.anchr.core.integration.constant.AliyunConstant.DEFAULT_REGION;
import static com.anchr.core.integration.constant.AliyunConstant.DEFAULT_ROLE_SESSION_NAME;
import static com.anchr.core.integration.constant.AliyunConstant.DEFAULT_STS_DURATION_SECONDS;

/**
 * Aliyun STS credential issuer for upload capability.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "object-storage", havingValue = "aliyun")
public class AliyunCredentialIssueService implements CredentialIssuePort {

    private final AliyunObjectStorageConfig aliyunObjectStorageConfig;

    @Override
    public IssuedCredential issueUploadCredential() {
        try {
            DefaultProfile profile = DefaultProfile.getProfile(
                    DEFAULT_REGION,
                    aliyunObjectStorageConfig.getAccessKeyId(),
                    aliyunObjectStorageConfig.getAccessKeySecret()
            );
            IAcsClient client = new DefaultAcsClient(profile);

            AssumeRoleRequest request = new AssumeRoleRequest();
            request.setRoleArn(aliyunObjectStorageConfig.getRoleArn());
            request.setRoleSessionName(DEFAULT_ROLE_SESSION_NAME);
            request.setDurationSeconds(DEFAULT_STS_DURATION_SECONDS);

            AssumeRoleResponse response = client.getAcsResponse(request);
            AssumeRoleResponse.Credentials credentials = Optional.ofNullable(response)
                    .map(AssumeRoleResponse::getCredentials)
                    .orElse(new AssumeRoleResponse.Credentials());

            return new IssuedCredential(
                    credentials.getAccessKeyId(),
                    credentials.getAccessKeySecret(),
                    credentials.getSecurityToken()
            );
        } catch (Exception e) {
            throw new BusinessException(ApiError.AUTH_STS_FETCH_FAILED, "Failed to issue upload credential.", e);
        }
    }
}
