package com.anchr.core.auth.infrastructure;

import com.google.gson.Gson;
import com.anchr.core.auth.application.OssService;
import com.anchr.core.auth.domain.port.CredentialIssuePort;
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
