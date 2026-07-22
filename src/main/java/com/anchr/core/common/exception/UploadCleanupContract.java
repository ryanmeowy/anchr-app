package com.anchr.core.common.exception;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the cleanup contract for an endpoint whose client may upload objects before invoking it.
 *
 * <p>Authentication, binding and validation failures happen before the controller is invoked and
 * are therefore cleanup-safe. Business errors must be listed explicitly, and may only be listed
 * when every path producing that error is guaranteed not to have committed a durable reference to
 * the uploaded objects.</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UploadCleanupContract {

    ApiError[] safeBusinessErrors() default {};
}
