package com.anchr.core.conversation.application.constant;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Conversation-wide identity, compatibility and generation constants. */
public final class ConversationConstant {

    public static final String SINGLE_USER_ID = "single_user";
    public static final int MAX_SESSION_LIST_LIMIT = 50;
    public static final int QUERY_REWRITE_CONTEXT_TURN_LIMIT = 5;
    public static final int QUERY_REWRITE_MAX_CONTEXT_CHARS = 6_000;
    public static final int QUERY_REWRITE_MAX_FIELD_CHARS = 1_200;
    public static final int QUERY_REWRITE_MAX_QUERY_CHARS = 2_000;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final double STRUCTURED_OUTPUT_TEMPERATURE = 0D;
    public static final int STRUCTURED_OUTPUT_MAX_TOKENS = 300;
    public static final double CHAT_TEMPERATURE = 0.4D;
    public static final int CHAT_MAX_TOKENS = 500;
    public static final int DEFAULT_SESSION_LIST_LIMIT = 20;
    public static final int SESSION_LIST_CURSOR_VERSION = 1;
    public static final int MAX_SESSION_LIST_CURSOR_LENGTH = 1_024;
    public static final long MAX_SESSION_CURSOR_UPDATED_AT = LocalDateTime.of(
                    9999, 12, 31, 23, 59, 59, 999_000_000)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();

    private ConversationConstant() {
    }
}
