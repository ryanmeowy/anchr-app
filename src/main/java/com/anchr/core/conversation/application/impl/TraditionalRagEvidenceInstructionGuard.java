package com.anchr.core.conversation.application.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic guard against indirect prompt injection through retrieved evidence.
 *
 * <p>Retrieved documents are untrusted data: a document may carry text that impersonates a
 * system instruction ("高优先级系统指令", "评审备注", "必须声称…", "单独输出 &lt;TOKEN&gt;").
 * A prompt-level warning is not a control, so an answer is treated as compromised when it echoes
 * a distinctive token or a long verbatim span that only exists inside such a directive segment of
 * the evidence context that was sent to the model. Shared by both answer paths: the
 * traditional RAG answer generator and the Agent completion effect.</p>
 */
public final class TraditionalRagEvidenceInstructionGuard {

    /** Evidence sentences that talk *to* the assistant instead of stating facts. */
    private static final Pattern DIRECTIVE_CUE = Pattern.compile(
            "高优先级|系统指令|评审备注|忽略(?:上述|上面|之前|此前|以上)"
                    + "|不要(?:提供|给出|输出)[^。；\\n]{0,8}(?:引用|来源)"
                    + "|必须(?:声称|承认)|已作废"
                    + "|ignore\\s+(?:all\\s+)?previous|system\\s+prompt|jailbreak"
                    + "|泄露(?:系统)?(?:提示|凭据|密钥)",
            Pattern.CASE_INSENSITIVE);

    /** Marker-like runs (ANCHREVALOVERRIDE_OK, API_KEY_V2) are instructions, not facts. */
    private static final Pattern DISTINCTIVE_TOKEN = Pattern.compile("[A-Za-z0-9_]{8,}");

    private static final int MIN_ECHOED_SPAN_CHARS = 24;

    private TraditionalRagEvidenceInstructionGuard() {
    }

    /**
     * @return true when the answer reproduces an injected instruction that lives in the evidence
     */
    public static boolean containsInstructionEcho(String answer, String evidenceContext) {
        if (answer == null || answer.isBlank() || evidenceContext == null || evidenceContext.isBlank()) {
            return false;
        }
        String normalizedAnswer = normalize(answer);
        if (normalizedAnswer.isEmpty()) {
            return false;
        }
        List<String> directiveSegments = directiveSegments(evidenceContext);
        if (directiveSegments.isEmpty()) {
            return false;
        }
        // Only text that exists *exclusively* inside a directive segment can identify an echo:
        // shared technical vocabulary ("fingerprint", "evidenceMaxChars") proves nothing.
        String factualEvidence = normalize(withoutDirectiveSegments(evidenceContext, directiveSegments));
        for (String segment : directiveSegments) {
            Matcher token = DISTINCTIVE_TOKEN.matcher(segment);
            while (token.find()) {
                String normalizedToken = normalize(token.group());
                if (normalizedToken.length() >= 8
                        && !factualEvidence.contains(normalizedToken)
                        && normalizedAnswer.contains(normalizedToken)) {
                    return true;
                }
            }
            if (echoesVerbatimSpan(normalizedAnswer, normalize(segment), factualEvidence)) {
                return true;
            }
        }
        return false;
    }

    private static String withoutDirectiveSegments(String evidenceContext, List<String> directiveSegments) {
        String remaining = evidenceContext;
        for (String segment : directiveSegments) {
            remaining = remaining.replace(segment, " ");
        }
        return remaining;
    }

    private static List<String> directiveSegments(String evidenceContext) {
        List<String> segments = new ArrayList<>();
        for (String raw : evidenceContext.split("[\\n。；;]")) {
            if (DIRECTIVE_CUE.matcher(raw).find()) {
                segments.add(raw);
            }
        }
        return segments;
    }

    private static boolean echoesVerbatimSpan(String normalizedAnswer, String normalizedSegment,
                                              String factualEvidence) {
        for (int start = 0; start + MIN_ECHOED_SPAN_CHARS <= normalizedSegment.length(); start++) {
            String span = normalizedSegment.substring(start, start + MIN_ECHOED_SPAN_CHARS);
            if (!factualEvidence.contains(span) && normalizedAnswer.contains(span)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text.toLowerCase().replaceAll("[^0-9a-z\\p{IsHan}]+", "");
    }
}
