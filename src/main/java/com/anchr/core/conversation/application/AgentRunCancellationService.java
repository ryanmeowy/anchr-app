package com.anchr.core.conversation.application;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.conversation.application.agent.AgentRunCancellationRegistry;
import com.anchr.core.conversation.application.agent.AgentRunStatus;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRunCancellationService {
    private final AgentTraceRepository traceRepository;
    private final ConversationRepository conversationRepository;
    private final AgentRunCancellationRegistry cancellationRegistry;

    public boolean cancel(String runId) {
        var run = traceRepository.findRun(runId)
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND));
        if (conversationRepository.findSession(run.getSessionId()).isEmpty()) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        if (AgentRunStatus.CANCELLED.name().equals(run.getStatus())) return true;
        if (!AgentRunStatus.RUNNING.name().equals(run.getStatus())) return false;
        return cancellationRegistry.cancel(runId);
    }
}
