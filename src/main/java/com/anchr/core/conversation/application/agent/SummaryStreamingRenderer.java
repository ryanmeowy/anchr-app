package com.anchr.core.conversation.application.agent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

import static com.anchr.core.conversation.application.constant.AnswerStreamConstant.TAIL_GUARD_CHARS;

/**
 * Converts opaque summary citation tokens into visible labels while retaining a tail that may
 * still contain a partial token or a whole-answer Markdown fence. Raw segment ids never leave this
 * component. The completed internal answer is returned separately for canonical validation.
 */
final class SummaryStreamingRenderer {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{cite:(\\d+)}}", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHOLE_MARKDOWN_FENCE = Pattern.compile(
            "(?is)^```[ \\t]*(?:markdown|md)?[ \\t]*\\R(.*?)\\R```[ \\t]*$");

    private final Map<String, String> tokenToSegment;
    private final Map<String, String> tokenToLabel;
    private final Set<String> segmentIds;
    private final Consumer<String> onVisibleDelta;
    private final StringBuilder raw = new StringBuilder();
    private String emitted = "";

    SummaryStreamingRenderer(Map<String, String> tokenToSegment,
                             Map<String, AgentCitationReference> references,
                             Consumer<String> onVisibleDelta) {
        this.tokenToSegment = Map.copyOf(new LinkedHashMap<>(tokenToSegment));
        Map<String, String> labels = new LinkedHashMap<>();
        tokenToSegment.forEach((token, segmentId) -> {
            AgentCitationReference reference = references.get(segmentId);
            if (reference == null) throw new IllegalArgumentException("Missing citation reference");
            labels.put(token, "[" + reference.label() + "]");
        });
        this.tokenToLabel = Map.copyOf(labels);
        this.segmentIds = Set.copyOf(new LinkedHashSet<>(tokenToSegment.values()));
        this.onVisibleDelta = onVisibleDelta == null ? ignored -> { } : onVisibleDelta;
    }

    void accept(String delta) {
        if (!StringUtils.hasText(delta)) return;
        raw.append(delta);
        rejectAuthoredOrInternalContent(raw.toString());
        String visible = decode(raw.toString(), tokenToLabel, false);
        visible = stripOpeningMarkdownFence(visible);
        visible = visible.length() <= TAIL_GUARD_CHARS
                ? "" : visible.substring(0, visible.length() - TAIL_GUARD_CHARS);
        if (!visible.startsWith(emitted)) {
            throw new IllegalStateException("Summary stream changed an emitted prefix");
        }
        if (visible.length() > emitted.length()) {
            String deltaToPublish = visible.substring(emitted.length());
            emitted = visible;
            onVisibleDelta.accept(deltaToPublish);
        }
    }

    String finishInternalAnswer() {
        return finishInternalAnswer(raw.toString());
    }

    String finishInternalAnswer(String completedRawAnswer) {
        String source = completedRawAnswer == null ? raw.toString() : completedRawAnswer;
        rejectAuthoredOrInternalContent(source);
        String internal = decode(source, internalTokens(), true);
        return unwrapMarkdownFence(internal);
    }

    private Map<String, String> internalTokens() {
        Map<String, String> values = new LinkedHashMap<>();
        tokenToSegment.forEach((token, segmentId) ->
                values.put(token, "{{segment:" + segmentId + "}}"));
        return values;
    }

    private void rejectAuthoredOrInternalContent(String source) {
        String normalized = source.toLowerCase(Locale.ROOT);
        if (normalized.contains("{{segment:")) {
            throw new IllegalStateException("Summary stream exposed an internal segment marker");
        }
        for (String segmentId : segmentIds) {
            if (StringUtils.hasText(segmentId) && source.contains(segmentId)) {
                throw new IllegalStateException("Summary stream exposed an internal segment id");
            }
        }
        for (String label : tokenToLabel.values()) {
            if (source.contains(label)) {
                throw new IllegalStateException("Summary stream authored a visible citation label");
            }
        }
    }

    private String decode(String source, Map<String, String> replacements, boolean complete) {
        Matcher matcher = TOKEN.matcher(source);
        StringBuilder decoded = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            int unknown = source.indexOf("{{", cursor);
            if (unknown >= 0 && unknown < matcher.start()) {
                throw new IllegalStateException("Summary stream returned an unknown internal token");
            }
            decoded.append(source, cursor, matcher.start());
            String token = matcher.group();
            String replacement = replacements.get(token);
            if (replacement == null) {
                throw new IllegalStateException("Summary stream returned an unknown citation token");
            }
            decoded.append(replacement);
            cursor = matcher.end();
        }
        int unknown = source.indexOf("{{", cursor);
        if (unknown >= 0) {
            if (complete || !isTokenPrefix(source.substring(unknown))) {
                throw new IllegalStateException("Summary stream returned an unknown internal token");
            }
            decoded.append(source, cursor, unknown);
            return decoded.toString();
        }
        decoded.append(source, cursor, source.length());
        return decoded.toString();
    }

    private boolean isTokenPrefix(String value) {
        if (!value.startsWith("{{")) return false;
        return tokenToSegment.keySet().stream().anyMatch(token -> token.startsWith(value));
    }

    private String stripOpeningMarkdownFence(String value) {
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
        String leading = value.substring(start);
        if ("```".startsWith(leading)) return "";
        if (!leading.startsWith("```")) return value;
        int lineEnd = leading.indexOf('\n');
        if (lineEnd < 0) return "";
        String language = leading.substring(3, lineEnd).trim();
        return language.isEmpty() || "markdown".equalsIgnoreCase(language) || "md".equalsIgnoreCase(language)
                ? leading.substring(lineEnd + 1) : value;
    }

    private String unwrapMarkdownFence(String value) {
        if (!StringUtils.hasText(value)) return value;
        String trimmed = value.trim();
        Matcher matcher = WHOLE_MARKDOWN_FENCE.matcher(trimmed);
        return matcher.matches() ? matcher.group(1).trim() : trimmed;
    }
}
