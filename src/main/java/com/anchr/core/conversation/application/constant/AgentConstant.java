package com.anchr.core.conversation.application.constant;

import java.time.Duration;

/** Agent workflow, tool and asynchronous-task policy constants. */
public final class AgentConstant {

    public static final int SUMMARY_MAX_DOCUMENTS = 3;

    public static final int HISTORY_LIMIT = 10;
    public static final int FIELD_LIMIT = 1_200;
    public static final int HISTORY_CHAR_LIMIT = 12_000;
    public static final int MAX_FINALIZER_EVIDENCE = 12;
    public static final int MAX_FINALIZER_EVIDENCE_CHARS = 24_000;
    public static final int FINALIZER_EVIDENCE_ITEM_CHARS = 2_000;
    public static final int FINALIZER_MAX_ATTEMPTS = 2;
    public static final long FINALIZER_MIN_REMAINING_MILLIS = 500L;
    public static final int MAX_MODEL_TOOL_RESULT_CHARS = 14_000;
    public static final int PLANNING_COMPACT_FIELD_CHARS = 1_000;
    public static final int PLANNING_EVIDENCE_CONTENT_CHARS = 700;
    public static final int MAX_READ_DOCUMENT_CALLS = 2;
    public static final int MAX_PROTOCOL_ERRORS = 2;

    public static final int MAX_UNIQUE_CITATIONS = 10;
    public static final int MAX_CITATION_MARKERS = 12;
    public static final int MAX_CITATION_MARKERS_PER_PARAGRAPH = 3;

    public static final long TASK_LEASE_MILLIS = Duration.ofMinutes(2).toMillis();
    public static final long TASK_POLL_INTERVAL_MILLIS = 5_000L;
    public static final int TASK_CLAIM_LIMIT = 4;
    public static final long TASK_RETRY_BASE_MILLIS = 30_000L;
    public static final long TASK_RETRY_MAX_MILLIS = 120_000L;
    public static final int CITATION_EVIDENCE_CHARS = 500;
    public static final int CITATION_CATALOG_CHARS = 8_000;

    public static final int MAX_CONTEXT_KNOWLEDGE_BASES = 50;
    public static final int MAX_CONTEXT_ASSETS = 20;
    public static final int MAX_CONTEXT_NAME_LENGTH = 300;
    public static final int AGENT_ACTIVITY_MAX_STEPS = 50;
    public static final int AGENT_ACTIVITY_DEFAULT_STEP_LIMIT = 20;

    public static final int FIND_DOCUMENTS_DEFAULT_LIMIT = 5;
    public static final int FIND_DOCUMENTS_MAX_LIMIT = 10;
    public static final int FIND_DOCUMENTS_QUERY_MAX_CHARS = 500;
    public static final int FIND_DOCUMENTS_SNIPPET_MAX_CHARS = 500;
    public static final int DOCUMENT_NAME_LOOKUP_LIMIT = 50;

    public static final int READ_DOCUMENT_DEFAULT_PAGE_SIZE = 20;
    public static final int READ_DOCUMENT_MIN_PAGE_SIZE = 10;
    public static final int READ_DOCUMENT_MAX_PAGE_SIZE = 20;
    public static final int READ_DOCUMENT_MAX_CONTENT_CHARS = 20_000;

    public static final int SEARCH_KNOWLEDGE_DEFAULT_LIMIT = 8;
    public static final int SEARCH_KNOWLEDGE_MAX_LIMIT = 10;
    public static final int SEARCH_KNOWLEDGE_QUERY_MAX_CHARS = 1_000;
    public static final int SEARCH_KNOWLEDGE_MAX_ASSETS = 100;
    public static final int SEARCH_KNOWLEDGE_MAX_MODALITIES = 3;
    public static final int SEARCH_KNOWLEDGE_CONTENT_MAX_CHARS = 2_000;

    public static final int SUMMARY_INSTRUCTION_MAX_CHARS = 2_000;
    public static final int SUMMARY_LANGUAGE_MAX_CHARS = 32;
    public static final int SUMMARY_READ_PAGE_SIZE = 20;
    public static final int SUMMARY_MAX_CITATIONS = 20;
    public static final int DELIVER_ANSWER_MAX_CHARS = 20_000;
    public static final int DELIVER_ANSWER_MAX_CITATIONS = 20;

    public static final double PLANNING_TEMPERATURE = 0.2D;
    public static final int PLANNING_MAX_TOKENS = 1_500;
    public static final double FINALIZER_TEMPERATURE = 0D;
    public static final int FINALIZER_MAX_TOKENS = 1_500;
    public static final double SUMMARY_TEMPERATURE = 0.2D;
    public static final int SUMMARY_MAX_TOKENS = 2_000;

    private AgentConstant() {
    }
}
