package com.anchr.core.conversation.application.acl;

import com.anchr.core.activity.application.api.ActivityRecordApi;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.common.application.context.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Conversation-side adapter for Activity capabilities. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationActivityAcl {

    private final ActivityRecordApi activityRecordApi;

    public void recordQuestionAsked(String sessionId, String turnId, String question, List<String> kbScope) {
        try {
            activityRecordApi.recordQuestionAsked(new ActivityRecordCommand.QuestionAsked(
                    UserContextHolder.get().userId(), sessionId, turnId, question, kbScope, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("question activity record failed, turnId={}", turnId, e);
        }
    }

    public void deleteBySessionId(String sessionId) {
        activityRecordApi.deleteBySessionId(sessionId);
    }
}
