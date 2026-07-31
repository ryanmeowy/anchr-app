package com.anchr.core.settings.application.support;

import com.anchr.core.settings.domain.model.RuntimeConfigType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RuntimeConfigCatalog {

    private static final int MAX_DOCLING_RESPONSE_MIB = 2047;

    public Map<String, String> defaults(RuntimeConfigType type) {
        return switch (type) {
            case SEARCH -> searchDefaults();
            case CONVERSATION -> conversationDefaults();
            case AGENT -> agentDefaults();
            case INGESTION -> ingestionDefaults();
            case OUTBOX -> outboxDefaults();
        };
    }

    public Set<String> keys(RuntimeConfigType type) {
        return defaults(type).keySet();
    }

    public void requireSupported(RuntimeConfigType type, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("runtime config key is required");
        }
        if (!keys(type).contains(key)) {
            throw new IllegalArgumentException(
                    "unsupported runtime config key for " + type + ": " + key);
        }
    }

    public String normalize(RuntimeConfigType type, String key, String rawValue) {
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
            throw new IllegalArgumentException("runtime config value is too long: " + key);
        }
        return normalized;
    }

    public void validateResolved(RuntimeConfigType type, Map<String, String> values) {
        for (String key : keys(type)) {
            normalize(type, key, required(values, key));
        }
        if (type == RuntimeConfigType.SEARCH) {
            int min = intValue(values, "windowMin");
            int max = intValue(values, "windowMax");
            if (min > max) {
                throw new IllegalArgumentException("windowMin must not exceed windowMax");
            }
            double alpha = doubleValue(values, "fusionAlpha");
            double beta = doubleValue(values, "fusionBeta");
            if (alpha + beta <= 0D) {
                throw new IllegalArgumentException(
                        "fusionAlpha and fusionBeta cannot both be zero");
            }
        }
        if (type == RuntimeConfigType.AGENT
                && intValue(values, "summaryBatchChars")
                > intValue(values, "summaryMaxChars")) {
            throw new IllegalArgumentException(
                    "summaryBatchChars must not exceed summaryMaxChars");
        }
    }

    private Map<String, String> searchDefaults() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("rankConstant", "60");
        values.put("candidateMultiplier", "4");
        values.put("maxCandidates", "200");
        values.put("textTopK", "80");
        values.put("documentImageTopK", "40");
        values.put("textSimilarity", "0.75");
        values.put("documentImageSimilarity", "0.7");
        values.put("maxDocChars", "1200");
        values.put("windowEnabled", "true");
        values.put("windowSize", "40");
        values.put("windowFactor", "3");
        values.put("windowMin", "20");
        values.put("windowMax", "80");
        values.put("fusionAlpha", "0.6");
        values.put("fusionBeta", "0.4");
        return values;
    }

    private Map<String, String> conversationDefaults() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("intentRoutingEnabled", "false");
        values.put("intentContextTurnLimit", "5");
        values.put("intentTimeoutSeconds", "5");
        values.put("legacyEvidenceFallbackEnabled", "false");
        return values;
    }

    private Map<String, String> agentDefaults() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("enabled", "true");
        values.put("toolCallMode", "AUTO");
        values.put("nativeToolChoice", "REQUIRED");
        values.put("fallbackToTraditional", "true");
        values.put("maxSteps", "12");
        values.put("maxToolCalls", "8");
        values.put("totalTimeoutSeconds", "90");
        values.put("modelTimeoutSeconds", "30");
        values.put("taskTimeoutSeconds", "600");
        values.put("taskModelTimeoutSeconds", "90");
        values.put("taskMaxRetries", "2");
        values.put("runtimeSnapshotTtlSeconds", "2100");
        values.put("summaryMaxDocuments", "3");
        values.put("summaryMaxSegments", "500");
        values.put("summaryMaxChars", "500000");
        values.put("summaryBatchChars", "12000");
        return values;
    }

    private Map<String, String> ingestionDefaults() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("claimBatchSize", "32");
        values.put("parsePollIntervalSeconds", "2");
        values.put("parseStageTimeoutMinutes", "45");
        values.put("stageMaxRetries", "5");
        values.put("embeddingMinIntervalMs", "1500");
        values.put("embeddingRateLimitMaxAttempts", "5");
        values.put("embeddingRateLimitBackoffMs", "5000");
        values.put("embeddedImageUploadEnabled", "false");
        values.put("doclingMaxResponseMiB", "256");
        return values;
    }

    private Map<String, String> outboxDefaults() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("batchSize", "20");
        values.put("maxAttempts", "10");
        values.put("retentionDays", "90");
        values.put("cleanupBatchSize", "1000");
        return values;
    }

    private String normalizeSearch(String key, String value) {
        return switch (key) {
            case "windowEnabled" -> booleanText(value, key);
            case "textSimilarity", "documentImageSimilarity",
                    "fusionAlpha", "fusionBeta" -> boundedDecimal(value, key);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeConversation(String key, String value) {
        return switch (key) {
            case "intentRoutingEnabled", "legacyEvidenceFallbackEnabled" ->
                    booleanText(value, key);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeAgent(String key, String value) {
        return switch (key) {
            case "enabled", "fallbackToTraditional" -> booleanText(value, key);
            case "toolCallMode" -> enumText(
                    value, key, Set.of("NATIVE", "JSON", "AUTO"));
            case "nativeToolChoice" -> enumText(
                    value, key, Set.of("AUTO", "REQUIRED"));
            case "taskMaxRetries" -> nonNegativeInteger(value, key);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeIngestion(String key, String value) {
        return switch (key) {
            case "embeddedImageUploadEnabled" -> booleanText(value, key);
            case "embeddingMinIntervalMs", "embeddingRateLimitBackoffMs" ->
                    nonNegativeLong(value, key);
            case "doclingMaxResponseMiB" ->
                    boundedInteger(value, key, 1, MAX_DOCLING_RESPONSE_MIB);
            default -> positiveInteger(value, key);
        };
    }

    private String normalizeOutbox(String key, String value) {
        return positiveLong(value, key);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing runtime config value: " + key);
        }
        return value;
    }

    private static int intValue(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static double doubleValue(Map<String, String> values, String key) {
        return Double.parseDouble(required(values, key));
    }

    private static String booleanText(String value, String key) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.toString(Boolean.parseBoolean(value));
    }

    private static String positiveInteger(String value, String key) {
        return boundedInteger(value, key, 1, Integer.MAX_VALUE);
    }

    private static String nonNegativeInteger(String value, String key) {
        return boundedInteger(value, key, 0, Integer.MAX_VALUE);
    }

    private static String boundedInteger(
            String value, String key, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        key + " must be between " + minimum + " and " + maximum);
            }
            return Integer.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static String positiveLong(String value, String key) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1L) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return Long.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static String nonNegativeLong(String value, String key) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) {
                throw new IllegalArgumentException(key + " must not be negative");
            }
            return Long.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static String boundedDecimal(String value, String key) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0D || parsed > 1D) {
                throw new IllegalArgumentException(key + " must be between 0 and 1");
            }
            return Double.toString(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private static String enumText(String value, String key, Set<String> allowed) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(
                    key + " must be one of " + String.join(", ", allowed));
        }
        return normalized;
    }
}
