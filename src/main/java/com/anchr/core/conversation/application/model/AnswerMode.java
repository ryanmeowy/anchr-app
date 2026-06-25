package com.anchr.core.conversation.application.model;

import org.springframework.util.StringUtils;

/**
 * Supported grounded answer modes.
 */
public enum AnswerMode {
    STRICT(new AnswerModePolicy(
            5,
            80,
            0.12D,
            false,
            "回答风格：先给简要结论，再给2-4条要点，结尾保留“参考来源”。只能基于证据回答，证据不足直接拒答。"
    )),
    SUMMARY(new AnswerModePolicy(
            3,
            60,
            0.10D,
            false,
            "回答风格：用简短结论和最多3条要点回答，避免展开推理过程，结尾保留最关键参考来源。只能基于证据回答。"
    )),
    EXPLORE(new AnswerModePolicy(
            5,
            40,
            0.08D,
            true,
            "回答风格：先列证据支持的信息，再单独列“可能方向/建议”。推测必须明确标注，不能把推测包装成事实。"
    ));

    private final AnswerModePolicy policy;

    AnswerMode(AnswerModePolicy policy) {
        this.policy = policy;
    }

    public AnswerModePolicy policy() {
        return policy;
    }

    public static AnswerMode from(String value) {
        if (!StringUtils.hasText(value)) {
            return STRICT;
        }
        try {
            return AnswerMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return STRICT;
        }
    }
}
