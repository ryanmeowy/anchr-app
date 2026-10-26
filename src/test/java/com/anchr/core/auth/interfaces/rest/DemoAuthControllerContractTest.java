package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.application.impl.DemoTokenService;
import com.anchr.core.auth.application.model.DemoUsage;
import com.anchr.core.auth.application.model.DemoTokenIssue;
import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.GlobalExceptionHandler;
import com.anchr.core.common.exception.ResultHttpStatusAdvice;
import com.anchr.core.common.infrastructure.DemoKillSwitch;
import com.anchr.core.common.exception.ApiErrorResponseWriter;
import com.anchr.core.common.exception.UploadCleanupPolicy;
import com.anchr.core.integration.security.AliyunCaptchaVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Path and JSON contract of the demo entry points.
 */
class DemoAuthControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Mirrors production wiring: the error envelope alone does not set the HTTP status; that is
     * done by {@link ResultHttpStatusAdvice}.
     */
    private MockMvc mvc(DemoAuthController controller) {
        return standaloneSetup(controller)
                .setControllerAdvice(
                        new GlobalExceptionHandler(
                                new ApiErrorResponseWriter(objectMapper),
                                new UploadCleanupPolicy()),
                        new ResultHttpStatusAdvice())
                .build();
    }

    private DemoAuthController controller(DemoProperties properties,
                                          DemoKillSwitch killSwitch,
                                          DemoTokenService tokenService,
                                          AliyunCaptchaVerifier verifier) {
        return new DemoAuthController(tokenService, properties, killSwitch, verifier);
    }

    private DemoProperties demoEnabled() {
        return new DemoProperties(true, 5, 8, 80, 50, 10, 1, false, "", "", "cn-shanghai", "", "", "", false, 3600, false);
    }

    @Test
    void issuingShouldReturnTokenAndSetTheBrowserCookie() throws Exception {
        DemoTokenService tokenService = mock(DemoTokenService.class);
        when(tokenService.issue(any())).thenReturn(
                new DemoTokenService.DemoTokenResult(
                        new DemoTokenIssue("tok", "demo_abc", System.currentTimeMillis() + 1000),
                        "browser-cookie", true));
        DemoKillSwitch killSwitch = mock(DemoKillSwitch.class);
        when(killSwitch.isOn()).thenReturn(false);
        AliyunCaptchaVerifier verifier = mock(AliyunCaptchaVerifier.class);
        when(verifier.verify(any())).thenReturn(true);

        DemoAuthController controller = controller(
                demoEnabled(), killSwitch, tokenService, verifier);
        MockMvc mockMvc = mvc(controller);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnstileToken\":\"cf-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("tok"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.userId").value("demo_abc"))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("anchr_demo_id=browser-cookie").contains("HttpOnly");
    }

    @Test
    void issuingShouldFailClosedWhenVerificationFails() throws Exception {
        DemoTokenService tokenService = mock(DemoTokenService.class);
        DemoKillSwitch killSwitch = mock(DemoKillSwitch.class);
        when(killSwitch.isOn()).thenReturn(false);
        AliyunCaptchaVerifier verifier = mock(AliyunCaptchaVerifier.class);
        when(verifier.verify(any())).thenReturn(false);

        DemoAuthController controller = controller(
                demoEnabled(), killSwitch, tokenService, verifier);
        MockMvc mockMvc = mvc(controller);

        mockMvc.perform(post("/api/v1/auth/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnstileToken\":\"bad\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode")
                        .value(ApiError.DEMO_VERIFICATION_FAILED.name()));
    }

    @Test
    void issuingShouldBeRefusedWhenTheDemoIsDisabled() throws Exception {
        DemoAuthController controller = controller(DemoProperties.defaults(),
                mock(DemoKillSwitch.class), mock(DemoTokenService.class),
                mock(AliyunCaptchaVerifier.class));
        MockMvc mockMvc = mvc(controller);

        mockMvc.perform(post("/api/v1/auth/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value(ApiError.DEMO_DISABLED.name()));
    }

    @Test
    void issuingShouldBeRefusedWhileTheKillSwitchIsOn() throws Exception {
        DemoKillSwitch killSwitch = mock(DemoKillSwitch.class);
        when(killSwitch.isOn()).thenReturn(true);
        DemoAuthController controller = controller(demoEnabled(), killSwitch,
                mock(DemoTokenService.class), mock(AliyunCaptchaVerifier.class));
        MockMvc mockMvc = mvc(controller);

        mockMvc.perform(post("/api/v1/auth/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value(ApiError.DEMO_DISABLED.name()));
    }

    @Test
    void statusShouldDescribeTheDemoWithoutLeakingSecrets() throws Exception {
        DemoKillSwitch killSwitch = mock(DemoKillSwitch.class);
        when(killSwitch.isOn()).thenReturn(false);
        DemoAuthController controller = controller(demoEnabled(), killSwitch,
                mock(DemoTokenService.class), mock(AliyunCaptchaVerifier.class));

        mvc(controller)
                .perform(get("/api/v1/auth/demo/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.tokenQuota").value(8))
                .andExpect(jsonPath("$.data.secretKey").doesNotExist());
    }

    @Test
    void usageShouldReportCreditsForTheCallersOwnToken() throws Exception {
        DemoTokenService service = mock(DemoTokenService.class);
        when(service.usage("demo-token"))
                .thenReturn(new DemoUsage(8, 3, System.currentTimeMillis() + 60_000));
        DemoAuthController controller = controller(demoEnabled(), mockOffKillSwitch(),
                service, mock(AliyunCaptchaVerifier.class));

        mvc(controller)
                .perform(get("/api/v1/auth/demo/usage").header("X-Access-Token", "demo-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quota").value(8))
                .andExpect(jsonPath("$.data.used").value(3))
                .andExpect(jsonPath("$.data.remaining").value(5));
    }

    @Test
    void usageShouldReturnNoPayloadForANonDemoToken() throws Exception {
        // The sidebar must not promise credits for an operator token.
        DemoTokenService service = mock(DemoTokenService.class);
        when(service.usage("operator-token")).thenReturn(null);
        DemoAuthController controller = controller(demoEnabled(), mockOffKillSwitch(),
                service, mock(AliyunCaptchaVerifier.class));

        mvc(controller)
                .perform(get("/api/v1/auth/demo/usage").header("X-Access-Token", "operator-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private DemoKillSwitch mockOffKillSwitch() {
        DemoKillSwitch killSwitch = mock(DemoKillSwitch.class);
        when(killSwitch.isOn()).thenReturn(false);
        return killSwitch;
    }

    @Test
    void killSwitchShouldRequireTheAdminSecret() throws Exception {
        DemoKillSwitch killSwitch = mock(DemoKillSwitch.class);
        DemoAuthController controller = controller(demoEnabled(), killSwitch,
                mock(DemoTokenService.class), mock(AliyunCaptchaVerifier.class));
        MockMvc mockMvc = mvc(controller);

        // No secret configured on this instance: the switch refuses rather than opening up.
        mockMvc.perform(post("/api/v1/auth/demo/kill-switch")
                        .header("X-Admin-Secret", "anything").param("on", "true"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode")
                        .value(ApiError.AUTH_ADMIN_SECRET_MISSING.name()));

        // A configured secret rejects a wrong value.
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "adminSecret", "expected-secret");
        mockMvc.perform(post("/api/v1/auth/demo/kill-switch")
                        .header("X-Admin-Secret", "wrong").param("on", "true"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ApiError.FORBIDDEN.name()));

        mockMvc.perform(post("/api/v1/auth/demo/kill-switch")
                        .header("X-Admin-Secret", "expected-secret").param("on", "true"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(killSwitch).set(true);
    }
}
