package com.anchr.core.conversation.application;

import com.anchr.core.conversation.domain.model.AgentTask;
import org.springframework.util.StringUtils;

/** Stable answer identity plus the local broker routing key. */
public record AnswerIdentity(
        String channelId,
        String answerId,
        String sessionId,
        String taskId,
        String runId,
        long revision
) {
    public AnswerIdentity {
        if (!StringUtils.hasText(channelId)) {
            throw new IllegalArgumentException("Answer channelId cannot be blank");
        }
        revision = Math.max(1, revision);
    }

    public static AnswerIdentity forTask(AgentTask task) {
        if (task == null) throw new IllegalArgumentException("Agent task cannot be null");
        return new AnswerIdentity(task.getTaskId(), task.getTurnId(), task.getSessionId(),
                task.getTaskId(), task.getRunId(), Math.max(1, task.getAttemptCount()));
    }
}
