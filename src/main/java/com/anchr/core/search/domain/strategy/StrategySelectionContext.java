package com.anchr.core.search.domain.strategy;

import lombok.Builder;
import lombok.Value;

import java.util.Optional;

/**
 * Thread-local strategy selection context for observability/debug headers.
 */
public final class StrategySelectionContext {
    private static final ThreadLocal<SelectionSnapshot> CONTEXT = new ThreadLocal<>();

    private StrategySelectionContext() {
    }

    public static void set(SelectionSnapshot snapshot) {
        CONTEXT.set(snapshot);
    }

    public static Optional<SelectionSnapshot> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static void clear() {
        CONTEXT.remove();
    }

    @Value
    @Builder
    public static class SelectionSnapshot {
        String requested;
        String effective;
        boolean fallback;
        String reason;
    }
}
