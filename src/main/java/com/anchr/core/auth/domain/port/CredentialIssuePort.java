package com.anchr.core.auth.domain.port;

/**
 * Auth-side outbound port for issuing temporary upload credentials.
 */
public interface CredentialIssuePort {

    IssuedCredential issueUploadCredential();

    record IssuedCredential(String accessKeyId, String accessKeySecret, String securityToken) {}
}
