package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.RequireAuth;
import com.anchr.core.auth.application.AccountService;
import com.anchr.core.auth.application.SessionTokenService;
import com.anchr.core.auth.domain.model.WorkspaceRole;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountApiController {

    private final AccountService accountService;

    @PostMapping("/login")
    public Result<AccountDTO> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(AccountDTO.from(accountService.login(request.getEmail(), request.getPassword())));
    }

    @RequireAuth
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("X-Access-Token") String token) {
        accountService.logout(token);
        return Result.success();
    }

    @RequireAuth
    @GetMapping("/me")
    public Result<AccountDTO> me(@RequestHeader("X-Access-Token") String token) {
        SessionTokenService.SessionPrincipal principal = accountService.me(token)
                .orElseThrow(() -> new BusinessException(ApiError.UNAUTHORIZED, "Invalid account session."));
        return Result.success(AccountDTO.from(principal));
    }

    @RequireAuth
    @PostMapping("/users")
    public Result<AccountDTO> createUser(@Valid @RequestBody CreateUserRequest request) {
        return Result.success(AccountDTO.from(accountService.createUser(request.getEmail(), request.getPassword(),
                request.getDisplayName(), WorkspaceRole.parse(request.getRole()))));
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String email;
        @NotBlank
        private String password;
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank
        private String email;
        @NotBlank
        private String password;
        private String displayName;
        private String role = "VIEWER";
    }

    @Value
    public static class AccountDTO {
        String token;
        String userId;
        String email;
        String displayName;
        String workspaceId;
        String role;

        static AccountDTO from(AccountService.LoginResult result) {
            return new AccountDTO(result.getToken(), result.getUserId(), result.getEmail(), result.getDisplayName(),
                    result.getWorkspaceId(), result.getRole());
        }

        static AccountDTO from(SessionTokenService.SessionPrincipal principal) {
            return new AccountDTO(null, principal.getUserId(), principal.getEmail(), principal.getDisplayName(),
                    principal.getWorkspaceId(), principal.getRole());
        }
    }
}
