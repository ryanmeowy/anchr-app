package com.anchr.core.auth.application.model;

/**
 * What a demo token issue attempt produced.
 */
public record DemoTokenIssue(String token, String userId, long expiresAt) {
}
