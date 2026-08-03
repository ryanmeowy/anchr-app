package com.anchr.core.settings.application.support;

import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.AgentRuntimeConfigKey;
import com.anchr.core.settings.domain.model.ConversationRuntimeConfigKey;
import com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey;
import com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey;
import com.anchr.core.settings.domain.model.SearchRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RuntimeConfigCatalog {

    private static final int MAX_DOCLING_RESPONSE_MIB = 2047;

    public Map<RuntimeConfigKey, String> defaults(RuntimeConfigType type) {
        return switch (type) {
            case SEARCH -> searchDefaults();
            case CONVERSATION -> conversationDefaults();
            case AGENT -> agentDefaults();
            case INGESTION -> ingestionDefaults();
            case OUTBOX -> outboxDefaults();
        };
    }

    public Set<RuntimeConfigKey> keys(RuntimeConfigType type) {
        return defaults(type).keySet();
    }

    public void requireSupported(RuntimeConfigType type, RuntimeConfigKey key) {
        key.requireType(type);
    }

    public String normalize(
            RuntimeConfigType type, RuntimeConfigKey key, String rawValue) {
        requireSupported(type, key);
        String value = rawValue == null ? "" : rawValue.trim();
        String normalized = switch (type) {
            case SEARCH -> normalizeSearch(key, value);
            case CONVERSATION -> normalizeConversation(key, value);
            case AGENT -> normalizeAgent(key, value);
            case INGESTION -> normalizeIngestion(key, value);
            case OUTBOX -> normalizeOutbox(key, value);
        };
        if (normalized.length() > 2048) {
            throw new IllegalArgumentException(
                    "runtime config value is too long: " + key.propertyName());
        }
        return normalized;
    }

    public void validateResolved(
            RuntimeConfigType type, Map<RuntimeConfigKey, String> values) {
        for (RuntimeConfigKey key : keys(type)) {
            normalize(type, key, required(values, key));
        }
        if (type == RuntimeConfigType.SEARCH) {
            int min = intValue(values, SearchRuntimeConfigKey.WINDOW_MIN);
            int max = intValue(values, SearchRuntimeConfigKey.WINDOW_MAX);
            if (min > max) {
                throw new IllegalArgumentException("windowMin must not exceed windowMax");
            }
            double alpha = doubleValue(values, SearchRuntimeConfigKey.FUSION_ALPHA);
            double beta = doubleValue(values, SearchRuntimeConfigKey.FUSION_BETA);
            if (alpha + beta <= 0D) {
                throw new IllegalArgumentException(
                        "fusionAlpha and fusionBeta cannot both be zero");
            }
        }
        if (type == RuntimeConfigType.AGENT
                && intValue(values, AgentRuntimeConfigKey.SUMMARY_BATCH_CHARS)
                > intValue(values, AgentRuntimeConfigKey.SUMMARY_MAX_CHARS)) {
            throw new IllegalArgumentException(
                    "summaryBatchChars must not exceed summaryMaxChars");
        }
        if (type == RuntimeConfigType.INGESTION
                && intValue(values, IngestionRuntimeConfigKey.CHUNK_MIN_TOKENS)
                > intValue(values, IngestionRuntimeConfigKey.CHUNK_MAX_TOKENS)) {
            throw new IllegalArgumentException(
                    "chunkMinTokens must not exceed chunkMaxTokens");
        }
    }

    private Map<RuntimeConfigKey, String> searchDefaults() {
        LinkedHashMap<RuntimeConfigKey, String> values = new LinkedHashMap<>();
        values.put(SearchRuntimeConfigKey.RANK_CONSTANT, "60");
        values.put(SearchRuntimeConfigKey.CANDIDATE_MULTIPLIER, "4");
        values.put(SearchRuntimeConfigKey.MAX_CANDIDATES, "200");
        values.put(SearchRuntimeConfigKey.TEXT_TOP_K, "80");
        values.put(SearchRuntimeConfigKey.DOCUMENT_IMAGE_TOP_K, "40");
        values.put(SearchRuntimeConfigKey.TEXT_SIMILARITY, "0.75");
        values.put(SearchRuntimeConfigKey.DOCUMENT_IMAGE_SIMILARITY, "0.7");
        values.put(SearchRuntimeConfigKey.MAX_DOC_CHARS, "1200");
        values.put(SearchRuntimeConfigKey.WINDOW_ENABLED, "true");
        values.put(SearchRuntimeConfigKey.WINDOW_SIZE, "40");
        values.put(SearchRuntimeConfigKey.WINDOW_FACTOR, "3");
        values.put(SearchRuntimeConfigKey.WINDOW_MIN, "20");
        values.put(SearchRuntimeConfigKey.WINDOW_MAX, "80");
        values.put(SearchRuntimeConfigKey.FUSION_ALPHA, "0.6");
        values.put(SearchRuntimeConfigKey.FUSION_BETA, "0.4");
        return values;
    }

    private Map<RuntimeConfigKey, String> conversationDefaults() {
        LinkedHashMap<RuntimeConfigKey, String> values = new LinkedHashMap<>();
        values.put(ConversationRuntimeConfigKey.INTENT_ROUTING_ENABLED, "false");
        values.put(ConversationRuntimeConfigKey.INTENT_CONTEXT_TURN_LIMIT, "5");
        values.put(ConversationRuntimeConfigKey.INTENT_TIMEOUT_SECONDS, "5");
        values.put(
                ConversationRuntimeConfigKey.LEGACY_EVIDENCE_FALLBACK_ENABLED, "false");
        return values;
    }

    private Map<RuntimeConfigKey, String> agentDefaults() {
        LinkedHashMap<RuntimeConfigKey, String> values = new LinkedHashMap<>();
        values.put(AgentRuntimeConfigKey.ENABLED, "true");
        values.put(AgentRuntimeConfigKey.TOOL_CALL_MODE, "AUTO");
        values.put(AgentRuntimeConfigKey.NATIVE_TOOL_CHOICE, "REQUIRED");
        values.put(AgentRuntimeConfigKey.FALLBACK_TO_TRADITIONAL, "true");
        values.put(AgentRuntimeConfigKey.MAX_STEPS, "12");
        values.put(AgentRuntimeConfigKey.MAX_TOOL_CALLS, "8");
        values.put(AgentRuntimeConfigKey.TOTAL_TIMEOUT_SECONDS, "90");
        values.put(AgentRuntimeConfigKey.MODEL_TIMEOUT_SECONDS, "30");
        values.put(AgentRuntimeConfigKey.TASK_TIMEOUT_SECONDS, "600");
        values.put(AgentRuntimeConfigKey.TASK_MODEL_TIMEOUT_SECONDS, "90");
        values.put(AgentRuntimeConfigKey.TASK_MAX_RETRIES, "2");
        values.put(AgentRuntimeConfigKey.RUNTIME_SNAPSHOT_TTL_SECONDS, "2100");
        values.put(AgentRuntimeConfigKey.SUMMARY_MAX_DOCUMENTS, "3");
        values.put(AgentRuntimeConfigKey.SUMMARY_MAX_SEGMENTS, "500");
        values.put(AgentRuntimeConfigKey.SUMMARY_MAX_CHARS, "500000");
        values.put(AgentRuntimeConfigKey.SUMMARY_BATCH_CHARS, "12000");
        return values;
    }

    private Map<RuntimeConfigKey, String> ingestionDefaults() {
        LinkedHashMap<RuntimeConfigKey, String> values = new LinkedHashMap<>();
        values.put(IngestionRuntimeConfigKey.CLAIM_BATCH_SIZE, "32");
        values.put(IngestionRuntimeConfigKey.PARSE_POLL_INTERVAL_SECONDS, "2");
        values.put(IngestionRuntimeConfigKey.PARSE_STAGE_TIMEOUT_MINUTES, "45");
        values.put(IngestionRuntimeConfigKey.STAGE_MAX_RETRIES, "5");
        values.put(IngestionRuntimeConfigKey.EMBEDDING_MIN_INTERVAL_MS, "1500");
        values.put(IngestionRuntimeConfigKey.EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS, "5");
        values.put(IngestionRuntimeConfigKey.EMBEDDING_RATE_LIMIT_BACKOFF_MS, "5000");
        values.put(IngestionRuntimeConfigKey.CHUNK_MIN_TOKENS, "200");
        values.put(IngestionRuntimeConfigKey.CHUNK_MAX_TOKENS, "1200");
        values.put(IngestionRuntimeConfigKey.EMBEDDED_IMAGE_UPLOAD_ENABLED, "false");
        values.put(IngestionRuntimeConfigKey.DOCLING_MAX_RESPONSE_MIB, "256");
        return values;
    }

    private Map<RuntimeConfigKey, String> outboxDefaults() {
        LinkedHashMap<RuntimeConfigKey, String> values = new LinkedHashMap<>();
        values.put(OutboxRuntimeConfigKey.BATCH_SIZE, "20");
        values.put(OutboxRuntimeConfigKey.MAX_ATTEMPTS, "10");
        values.put(OutboxRuntimeConfigKey.RETENTION_DAYS, "90");
        values.put(OutboxRuntimeConfigKey.CLEANUP_BATCH_SIZE, "1000");
        return values;
    }

    private String normalizeSearch(RuntimeConfigKey key, String value) {
        SearchRuntimeConfigKey searchKey = (SearchRuntimeConfigKey) key;
        return switch (searchKey) {
            case WINDOW_ENABLED -> booleanText(value, key);
            case TEXT_SIMILARITY, DOCUMENT_IMAGE_SIMILARITY,
                    FUSION_ALPHA, FUSION_BETA -> boundedDecimal(value, key);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeConversation(RuntimeConfigKey key, String value) {
        ConversationRuntimeConfigKey conversationKey =
                (ConversationRuntimeConfigKey) key;
        return switch (conversationKey) {
            case INTENT_ROUTING_ENABLED, LEGACY_EVIDENCE_FALLBACK_ENABLED ->
                    booleanText(value, key);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeAgent(RuntimeConfigKey key, String value) {
        AgentRuntimeConfigKey agentKey = (AgentRuntimeConfigKey) key;
        return switch (agentKey) {
            case ENABLED, FALLBACK_TO_TRADITIONAL -> booleanText(value, key);
            case TOOL_CALL_MODE -> enumText(
                    value, key, Set.of("NATIVE", "JSON", "AUTO"));
            case NATIVE_TOOL_CHOICE -> enumText(
                    value, key, Set.of("AUTO", "REQUIRED"));
            case TASK_MAX_RETRIES -> nonNegativeInteger(value, key);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeIngestion(RuntimeConfigKey key, String value) {
        IngestionRuntimeConfigKey ingestionKey = (IngestionRuntimeConfigKey) key;
        return switch (ingestionKey) {
            case EMBEDDED_IMAGE_UPLOAD_ENABLED -> booleanText(value, key);
            case EMBEDDING_MIN_INTERVAL_MS, EMBEDDING_RATE_LIMIT_BACKOFF_MS ->
                    nonNegativeLong(value, key);
            case DOCLING_MAX_RESPONSE_MIB ->
                    boundedInteger(value, key, 1, MAX_DOCLING_RESPONSE_MIB);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeOutbox(RuntimeConfigKey key, String value) {
        return positiveLong(value, key);
    }

    private static String required(
            Map<RuntimeConfigKey, String> values, RuntimeConfigKey key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "missing runtime config value: " + key.propertyName());
        }
        return value;
    }

    private static int intValue(
            Map<RuntimeConfigKey, String> values, RuntimeConfigKey key) {
        return Integer.parseInt(required(values, key));
    }

    private static double doubleValue(
            Map<RuntimeConfigKey, String> values, RuntimeConfigKey key) {
        return Double.parseDouble(required(values, key));
    }

    private static String booleanText(String value, RuntimeConfigKey key) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(
                    key.propertyName() + " must be true or false");
        }
        return Boolean.toString(Boolean.parseBoolean(value));
    }

    private static String positiveInteger(String value, RuntimeConfigKey key) {
        return boundedInteger(value, key, 1, Integer.MAX_VALUE);
    }

    private static String nonNegativeInteger(String value, RuntimeConfigKey key) {
        return boundedInteger(value, key, 0, Integer.MAX_VALUE);
    }

    private static String boundedInteger(
            String value, RuntimeConfigKey key, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        key.propertyName() + " must be between "
                                + minimum + " and " + maximum);
            }
            return Integer.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    key.propertyName() + " must be an integer");
        }
    }

    private static String positiveLong(String value, RuntimeConfigKey key) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1L) {
                throw new IllegalArgumentException(
                        key.propertyName() + " must be positive");
            }
            return Long.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    key.propertyName() + " must be an integer");
        }
    }

    private static String nonNegativeLong(String value, RuntimeConfigKey key) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) {
                throw new IllegalArgumentException(
                        key.propertyName() + " must not be negative");
            }
            return Long.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    key.propertyName() + " must be an integer");
        }
    }

    private static String boundedDecimal(String value, RuntimeConfigKey key) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0D || parsed > 1D) {
                throw new IllegalArgumentException(
                        key.propertyName() + " must be between 0 and 1");
            }
            return Double.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    key.propertyName() + " must be a number");
        }
    }

    private static String enumText(
            String value, RuntimeConfigKey key, Set<String> allowed) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(
                    key.propertyName() + " must be one of "
                            + String.join(", ", allowed));
        }
        return normalized;
    }
}
