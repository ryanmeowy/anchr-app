package com.anchr.core.auth.application;

import com.anchr.core.auth.domain.model.WorkspaceRole;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * Application-level permission checks for workspace operations.
 */
@Service
public class PermissionService {

    public void requireImport() {
        if (!role().canImport()) {
            throw new BusinessException(ApiError.FORBIDDEN, "Current user cannot import documents.");
        }
    }

    public void requireDelete() {
        if (!role().canDelete()) {
            throw new BusinessException(ApiError.FORBIDDEN, "Current user cannot delete resources.");
        }
    }

    public void requireManageSettings() {
        if (!role().canManageSettings()) {
            throw new BusinessException(ApiError.FORBIDDEN, "Current user cannot manage settings.");
        }
    }

    public void requireManageMembers() {
        if (!role().canManageMembers()) {
            throw new BusinessException(ApiError.FORBIDDEN, "Current user cannot manage workspace members.");
        }
    }

    private WorkspaceRole role() {
        return WorkspaceRole.parse(UserContextHolder.get().role());
    }
}
