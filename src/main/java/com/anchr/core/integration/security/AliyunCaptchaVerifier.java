package com.anchr.core.integration.security;

import com.aliyuncs.captcha.model.v20230305.VerifyIntelligentCaptchaRequest;
import com.aliyuncs.captcha.model.v20230305.VerifyIntelligentCaptchaResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.anchr.core.common.config.DemoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of Alibaba Cloud CAPTCHA 2.0 results.
 *
 * <p>The browser solves the puzzle and hands back <em>captchaVerifyParam</em>, which is only a
 * claim; it must be exchanged for a verdict by calling {@code VerifyIntelligentCaptcha} with the
 * AccessKey credentials. The result is never cached: the parameter is tied to one challenge.
 *
 * <p>Alibaba Cloud runs in mainland China, which is why this replaced Cloudflare Turnstile: the
 * Turnstile script was unreliable for visitors without a proxy.
 */
@Slf4j
@Component
public class AliyunCaptchaVerifier {

    private static final String ENDPOINT = "captcha.cn-shanghai.aliyuncs.com";

    private final DemoProperties demoProperties;

    /**
     * Built lazily and kept for the life of the bean. Only tests reassign it; production always
     * uses the real client, and the field keeps the single-constructor rule that
     * {@code SpringBeanConstructorContractTest} enforces.
     */
    IAcsClient client;

    public AliyunCaptchaVerifier(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    /**
     * @return true only when Alibaba Cloud explicitly reports a passing verification
     */
    public boolean verify(String captchaVerifyParam) {
        if (!demoProperties.captchaVerificationActive()) {
            log.warn("CAPTCHA verification is DISABLED; not for production use.");
            return true;
        }
        if (captchaVerifyParam == null || captchaVerifyParam.isBlank()) {
            return false;
        }
        if (!demoProperties.hasCaptchaCredentials()) {
            log.error("CAPTCHA credentials are missing while verification is active.");
            return false;
        }
        try {
            VerifyIntelligentCaptchaRequest request = new VerifyIntelligentCaptchaRequest();
            request.setSceneId(demoProperties.captchaSceneId());
            request.setCaptchaVerifyParam(captchaVerifyParam);
            // The SDK resolves product endpoints from a table shipped inside sdk-core, and the
            // version pulled in transitively has no "captcha" entry. Setting the endpoint
            // explicitly avoids pinning sdk-core forward and disturbing the OSS/STS clients.
            request.setEndpoint(ENDPOINT);
            VerifyIntelligentCaptchaResponse response = client().getAcsResponse(request);
            if (response == null) {
                log.warn("CAPTCHA verification returned no response");
                return false;
            }
            Boolean passed = response.getResult() == null ? null : response.getResult().getVerifyResult();
            // The API reports transport success separately from the verdict; a request can succeed
            // while the challenge itself fails, so both levels are required.
            boolean ok = Boolean.TRUE.equals(response.getSuccess()) && Boolean.TRUE.equals(passed);
            if (!ok) {
                log.info("CAPTCHA rejected the token, code={}, verifyCode={}",
                        response.getCode(),
                        response.getResult() == null ? null : response.getResult().getVerifyCode());
            }
            return ok;
        } catch (ClientException exception) {
            // Fail closed: an unreachable or misconfigured verifier must not let traffic through.
            log.warn("CAPTCHA verification call failed, code={}, message={}",
                    exception.getErrCode(), exception.getErrMsg());
            return false;
        } catch (RuntimeException exception) {
            log.warn("CAPTCHA verification failed: {}", exception.getMessage());
            return false;
        }
    }

    private IAcsClient client() {
        if (client == null) {
            DefaultProfile profile = DefaultProfile.getProfile(
                    demoProperties.captchaRegion(),
                    demoProperties.captchaAccessKeyId(),
                    demoProperties.captchaAccessKeySecret());
            client = new DefaultAcsClient(profile);
        }
        return client;
    }

}
