package com.anchr.core.auth.domain.model;

import java.util.Locale;

/**
 * Workspace role used by Phase 4 permission checks.
 */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    EDITOR,
    VIEWER;

    public static WorkspaceRole parse(String value) {
        if (value == null || value.isBlank()) {
            return VIEWER;
        }
        return WorkspaceRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean canImport() {
        return this == OWNER || this == ADMIN || this == EDITOR;
    }

    public boolean canDelete() {
        return this == OWNER || this == ADMIN || this == EDITOR;
    }

    public boolean canManageSettings() {
        return this == OWNER || this == ADMIN;
    }

    public boolean canManageMembers() {
        return this == OWNER || this == ADMIN;
    }
}
