package com.anchr.core.conversation.application.constant;

/** Shared transport constants for conversation and asynchronous Agent answer streams. */
public final class AnswerStreamConstant {

    public static final long CONVERSATION_TIMEOUT_MILLIS = 120_000L;
    public static final long AGENT_TASK_TIMEOUT_MILLIS = 11 * 60_000L;
    public static final String PADDING = " ".repeat(2_048);
    public static final int TAIL_GUARD_CHARS = 96;

    private AnswerStreamConstant() {
    }
}
