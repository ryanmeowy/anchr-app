package com.anchr.core.auth.application.model;

/**
 * Result of reading a caller's own demo token usage.
 */
public record DemoUsage(int quota, int used, long expiresAt) {

    public int remaining() {
        return Math.max(0, quota - used);
    }
}
