package com.smart.vision.core.auth.infrastructure;

import com.google.gson.Gson;
import com.smart.vision.core.auth.application.OssService;
import com.smart.vision.core.auth.domain.port.CredentialIssuePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Auth-side application service implementation.
 * Vendor-specific credential issuing is delegated to integration via port.
 */
@Service
@RequiredArgsConstructor
public class OssServiceImpl implements OssService {

    private final CredentialIssuePort credentialIssuePort;
    private final Gson gson;
    private final AesUtil aesUtil;

    @Override
    public String fetchStsToken() {
        CredentialIssuePort.IssuedCredential credential = credentialIssuePort.issueUploadCredential();
        String json = gson.toJson(credential);
        return aesUtil.encrypt(json);
    }
}
