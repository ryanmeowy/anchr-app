package com.anchr.core.common.application.context;

/**
 * Holds request user context for application services.
 */
public final class UserContextHolder {

    private static final ThreadLocal<RequestUserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(RequestUserContext context) {
        HOLDER.set(context);
    }

    public static RequestUserContext get() {
        RequestUserContext context = HOLDER.get();
        return context == null ? RequestUserContext.systemDefault() : context;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
