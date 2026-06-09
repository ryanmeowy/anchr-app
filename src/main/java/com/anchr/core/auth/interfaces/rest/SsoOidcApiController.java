package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.application.OidcSsoService;
import com.anchr.core.auth.application.SessionTokenService;
import com.anchr.core.auth.infrastructure.RequireAuth;
import com.anchr.core.common.model.Result;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sso")
@RequiredArgsConstructor
public class SsoOidcApiController {

    private final OidcSsoService oidcSsoService;
    private final SessionTokenService sessionTokenService;

    @GetMapping("/oidc/login")
    public Result<LoginUrlDTO> oidcLogin() {
        return Result.success(new LoginUrlDTO(oidcSsoService.loginUrl()));
    }

    @GetMapping("/oidc/callback")
    public Result<AccountApiController.AccountDTO> oidcCallback(@RequestParam("idToken") @NotBlank String idToken) {
        OidcSsoService.SsoLoginResult result = oidcSsoService.callback(idToken);
        return Result.success(new AccountApiController.AccountDTO(result.getToken(), result.getUserId(),
                result.getEmail(), result.getDisplayName(), result.getWorkspaceId(), result.getRole()));
    }

    @RequireAuth
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("X-Access-Token") String token) {
        sessionTokenService.revoke(token);
        return Result.success();
    }

    @Value
    public static class LoginUrlDTO {
        String authorizationUrl;
    }
}
