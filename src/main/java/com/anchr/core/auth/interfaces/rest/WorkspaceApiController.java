package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.RequireAuth;
import com.anchr.core.auth.application.WorkspaceService;
import com.anchr.core.auth.domain.model.WorkspaceRole;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceMemberRecord;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceRecord;
import com.anchr.core.common.model.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceApiController {

    private final WorkspaceService workspaceService;

    @RequireAuth
    @GetMapping
    public Result<List<WorkspaceRecord>> list() {
        return Result.success(workspaceService.list());
    }

    @RequireAuth
    @PostMapping
    public Result<WorkspaceRecord> create(@Valid @RequestBody WorkspaceCreateRequest request) {
        return Result.success(workspaceService.create(request.getName()));
    }

    @RequireAuth
    @GetMapping("/{workspaceId}/members")
    public Result<List<WorkspaceMemberRecord>> members(@PathVariable String workspaceId) {
        return Result.success(workspaceService.listMembers(workspaceId));
    }

    @RequireAuth
    @PostMapping("/{workspaceId}/members")
    public Result<WorkspaceMemberRecord> addMember(@PathVariable String workspaceId,
                                                   @Valid @RequestBody MemberCreateRequest request) {
        return Result.success(workspaceService.addMember(workspaceId, request.getEmail(),
                WorkspaceRole.parse(request.getRole())));
    }

    @RequireAuth
    @PatchMapping("/{workspaceId}/members/{userId}")
    public Result<WorkspaceMemberRecord> updateMember(@PathVariable String workspaceId,
                                                      @PathVariable String userId,
                                                      @Valid @RequestBody MemberUpdateRequest request) {
        return Result.success(workspaceService.updateRole(workspaceId, userId, WorkspaceRole.parse(request.getRole())));
    }

    @RequireAuth
    @DeleteMapping("/{workspaceId}/members/{userId}")
    public Result<Void> removeMember(@PathVariable String workspaceId, @PathVariable String userId) {
        workspaceService.removeMember(workspaceId, userId);
        return Result.success();
    }

    @Data
    public static class WorkspaceCreateRequest {
        @NotBlank
        private String name;
    }

    @Data
    public static class MemberCreateRequest {
        @NotBlank
        private String email;
        @NotBlank
        private String role;
    }

    @Data
    public static class MemberUpdateRequest {
        @NotBlank
        private String role;
    }
}
