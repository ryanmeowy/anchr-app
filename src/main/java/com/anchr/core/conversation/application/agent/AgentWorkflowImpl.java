package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.ConversationProgressListener;
import com.anchr.core.conversation.application.model.ConversationExecutionMode;
import com.anchr.core.conversation.application.model.ConversationExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AgentWorkflowImpl implements AgentWorkflow {
    private final AgentRunInitializer initializer;
    private final AgentTransitionEngine transitionEngine;
    private final AgentEffectRunner effectRunner;
    private final AgentRunObserver observer;
    private final AgentRunCancellationRegistry cancellationRegistry;

    public AgentWorkflowImpl(AgentRunInitializer initializer,
                             AgentTransitionEngine transitionEngine,
                             AgentEffectRunner effectRunner,
                             AgentRunObserver observer,
                             AgentRunCancellationRegistry cancellationRegistry) {
        this.initializer = initializer;
        this.transitionEngine = transitionEngine;
        this.effectRunner = effectRunner;
        this.observer = observer;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Override
    public ConversationExecutionResult execute(AgentRunRequest request,
                                               ConversationProgressListener listener) {
        ConversationProgressListener progress = listener == null
                ? ConversationProgressListener.NOOP : listener;
        boolean registered = false;
        try {
            AgentState state = initializer.initialize(request, progress.supportsAnswerStreaming(),
                    System.currentTimeMillis());
            cancellationRegistry.register(request.runId(), request.sessionId());
            registered = true;
            AgentEvent event = new AgentEvent.RunStarted(System.currentTimeMillis());
            while (true) {
                AgentState previousState = state;
                AgentTransition transition = transitionEngine.transition(state, event);
                if (transition.terminal() != null
                        && transition.terminal().runStatus() != AgentRunStatus.CANCELLED
                        && !cancellationRegistry.tryClaimTerminal(request.runId())) {
                    event = new AgentEvent.CancellationRequested(System.currentTimeMillis());
                    continue;
                }
                state = transition.nextState();
                observer.observe(previousState, state, transition.signals(), progress);
                if (transition.terminal() != null) {
                    return finish(state, transition.terminal());
                }
                AgentCommand command = transition.command();
                if (command == null) {
                    throw new IllegalStateException("Non-terminal Agent transition did not produce a command");
                }
                if (cancellationRequested(request.runId())) {
                    event = new AgentEvent.CancellationRequested(System.currentTimeMillis());
                    continue;
                }
                AgentEvent effectEvent = effectRunner.execute(state, command, progress);
                event = cancellationRequested(request.runId())
                        ? new AgentEvent.CancellationRequested(System.currentTimeMillis()) : effectEvent;
            }
        } catch (AgentWorkflowException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Agent workflow could not start or terminated unexpectedly, runId={}",
                    request.runId(), e);
            throw new AgentWorkflowException("agent_workflow_failed", e);
        } finally {
            try {
                if (registered) cancellationRegistry.unregister(request.runId());
            } finally {
                Thread.interrupted();
            }
        }
    }

    private ConversationExecutionResult finish(AgentState state, AgentTerminal terminal) {
        if (terminal.runStatus() == AgentRunStatus.FAILED) {
            RuntimeException cause = terminal.cause() == null
                    ? new IllegalStateException(terminal.fallbackReason()) : terminal.cause();
            log.error("Agent workflow failed, runId={}", state.runRequest().runId(), cause);
            throw new AgentWorkflowException("agent_workflow_failed", cause);
        }
        return new ConversationExecutionResult(null, !state.evidence().isEmpty(), null,
                terminal.answer(), terminal.answerStatus(), terminal.fallbackReason(),
                terminal.citations(), List.of(), null, state.runRequest().runId(),
                ConversationExecutionMode.AGENT, terminal.deferredTask());
    }

    private boolean cancellationRequested(String runId) {
        return cancellationRegistry.isCancellationRequested(runId);
    }
}
