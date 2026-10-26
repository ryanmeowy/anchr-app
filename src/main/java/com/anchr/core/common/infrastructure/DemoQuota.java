package com.anchr.core.common.infrastructure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose successful entry costs one demo credit.
 *
 * <p>Deliberately carries no cost field: an Agent question and a plain question are billed the
 * same, so the check only needs to know that a billable call happened. That keeps the quota
 * decision inside the interceptor, away from the request body and the service layer.
 *
 * <p>A handler without this annotation is never billed, so forgetting to annotate a new endpoint
 * under-charges instead of blocking a visitor.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DemoQuota {
}
