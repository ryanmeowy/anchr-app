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
            "回答风格：先用1-2句话直接回答问题，再给2-4条可由证据直接支持的要点。"
                    + "不得扩展证据之外的背景知识，不得使用可能、大概等方式掩盖证据不足。"
                    + "证据不能直接支持核心结论时必须拒答。"
    )),
    SUMMARY(new AnswerModePolicy(
            3,
            60,
            0.10D,
            false,
            "回答风格：先给一句综合结论，再用最多3条要点概括证据中的共同信息、差异或关键事实。"
                    + "不得逐段复述证据，不展示推理过程，不引入证据之外的信息。"
                    + "证据无法形成可靠摘要时必须拒答。"
    )),
    EXPLORE(new AnswerModePolicy(
            5,
            40,
            0.08D,
            true,
            "回答风格：先列出证据可以确认的事实，再按需增加独立的“可能方向/建议”段落。"
                    + "该段中的每一项都必须明确使用“可能”“建议验证”等措辞，并说明它不是证据已确认的事实。"
                    + "不得用推测补齐缺失的核心答案；核心问题无证据时仍必须拒答。"
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
