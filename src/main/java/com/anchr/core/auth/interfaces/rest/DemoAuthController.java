package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.application.impl.DemoTokenService;
import com.anchr.core.auth.interfaces.rest.dto.DemoTokenDTO;
import com.anchr.core.auth.interfaces.rest.dto.DemoStatusDTO;
import com.anchr.core.auth.interfaces.rest.dto.DemoUsageDTO;
import com.anchr.core.auth.application.model.DemoUsage;
import com.anchr.core.auth.interfaces.rest.dto.DemoTokenRequestDTO;
import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.constant.DemoConstant;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.infrastructure.DemoKillSwitch;
import com.anchr.core.common.infrastructure.PermitAll;
import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import com.anchr.core.integration.security.AliyunCaptchaVerifier;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo entry points: token issuing and the kill switch.
 *
 * <p>Kept separate from {@link AuthController} on purpose. That controller is built with three
 * constructor arguments in its HTTP contract test and its token model has no visitor identity;
 * extending it would both break that test and blur two different lifecycles.
 */
@Validated
@RestController
@RequestMapping("/api/v1/auth/demo")
@RequiredArgsConstructor
@Slf4j
public class DemoAuthController {

    private final DemoTokenService demoTokenService;
    private final DemoProperties demoProperties;
    private final DemoKillSwitch killSwitch;
    private final AliyunCaptchaVerifier captchaVerifier;

    @Value("${app.security.admin-secret:}")
    private String adminSecret;

    @PermitAll
    @GetMapping("/status")
    public Result<DemoStatusDTO> status() {
        return Result.success(new DemoStatusDTO(
                demoProperties.enabled(),
                killSwitch.isOn(),
                demoProperties.tokenQuota(),
                demoProperties.sceneId(),
                demoProperties.prefix(),
                demoProperties.encryptedSceneIdForClient()));
    }

    /**
     * Usage of the caller's own demo token, so the UI can show remaining credits and a countdown.
     * Requires a token; an expired or non-demo token yields a null payload rather than an error,
     * because "no usable token" is a normal state the entry card handles.
     */
    @RequireAuth(roles = {"ADMIN", "USER"})
    @GetMapping("/usage")
    public Result<DemoUsageDTO> usage(@RequestHeader(value = "X-Access-Token", required = false)
                                      String accessToken) {
        DemoUsage usage = demoTokenService.usage(accessToken);
        if (usage == null) {
            return Result.success(null);
        }
        return Result.success(new DemoUsageDTO(
                usage.quota(), usage.used(), usage.remaining(), usage.expiresAt()));
    }

    @PermitAll
    @PostMapping
    public Result<DemoTokenDTO> issue(@Valid @RequestBody(required = false) DemoTokenRequestDTO request,
                                      HttpServletRequest servletRequest,
                                      HttpServletResponse servletResponse) {
        if (!demoProperties.enabled()) {
            return Result.error(ApiError.DEMO_DISABLED);
        }
        if (killSwitch.isOn()) {
            return Result.error(ApiError.DEMO_DISABLED);
        }
        String captchaVerifyParam = request == null ? null : request.getCaptchaVerifyParam();
        if (!captchaVerifier.verify(captchaVerifyParam)) {
            return Result.error(ApiError.DEMO_VERIFICATION_FAILED);
        }

        DemoTokenService.DemoTokenResult result =
                demoTokenService.issue(readBrowserCookie(servletRequest));
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, browserCookie(result.browserCookie()));
        return Result.success(DemoTokenDTO.builder()
                .token(result.issue().token())
                .role("USER")
                .userId(result.issue().userId())
                .expiresAt(result.issue().expiresAt())
                .build());
    }

    @PermitAll
    @PostMapping("/kill-switch")
    public Result<Boolean> killSwitch(@RequestHeader("X-Admin-Secret") String secret,
                                      @RequestParam boolean on) {
        if (!StringUtils.hasText(adminSecret)) {
            log.error("Admin secret is not configured");
            return Result.error(ApiError.AUTH_ADMIN_SECRET_MISSING);
        }
        if (!constantTimeEquals(adminSecret, secret)) {
            return Result.error(ApiError.FORBIDDEN);
        }
        killSwitch.set(on);
        return Result.success(on);
    }

    private String readBrowserCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (DemoConstant.BROWSER_COOKIE.equals(cookie.getName())
                    && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String browserCookie(String value) {
        return ResponseCookie.from(DemoConstant.BROWSER_COOKIE, value)
                .httpOnly(true)
                // Browsers drop a Secure cookie over plain HTTP. The demo runs behind HTTPS, but
                // local development with Turnstile off would then re-issue a token on every load
                // and quietly burn the daily issue budget.
                .secure(demoProperties.captchaVerificationActive())
                .sameSite("Lax")
                .path("/")
                .maxAge(java.time.Duration.ofHours(demoProperties.tokenTtlHours()))
                .build()
                .toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

}
