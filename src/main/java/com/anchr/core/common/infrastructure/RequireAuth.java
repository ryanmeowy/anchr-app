package com.anchr.core.common.infrastructure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Require Auth annotation for controller methods
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuth {
    /**
     * Allowed roles. Defaults to ADMIN only.
     * Add GUEST to allow read-only access.
     */
    String[] roles() default {"ADMIN"};
}
