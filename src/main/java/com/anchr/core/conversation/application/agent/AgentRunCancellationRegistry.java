package com.anchr.core.conversation.application.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRunCancellationRegistry {
    private final Map<String, RunningRun> runningRuns = new ConcurrentHashMap<>();
    private final Set<String> cancellationRequests = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();

    public void register(String runId, String sessionId) {
        if (StringUtils.hasText(runId)) {
            synchronized (lifecycleMonitor) {
                runningRuns.put(runId, new RunningRun(Thread.currentThread(), sessionId));
            }
        }
    }

    public boolean cancel(String runId) {
        if (!StringUtils.hasText(runId)) return false;
        synchronized (lifecycleMonitor) {
            cancellationRequests.add(runId);
            RunningRun running = runningRuns.get(runId);
            if (running == null) {
                cancellationRequests.remove(runId);
                return false;
            }
            running.thread().interrupt();
            return true;
        }
    }

    public void cancelBySessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return;
        runningRuns.forEach((runId, running) -> {
            if (sessionId.equals(running.sessionId())) cancel(runId);
        });
    }

    public boolean isCancellationRequested(String runId) {
        return StringUtils.hasText(runId) && cancellationRequests.contains(runId);
    }

    /**
     * Atomically decides whether a non-cancelled terminal result owns the Run.
     * Once claimed, a later cancel request observes no running Run and cannot
     * overwrite the committed terminal outcome.
     */
    public boolean tryClaimTerminal(String runId) {
        if (!StringUtils.hasText(runId)) return true;
        synchronized (lifecycleMonitor) {
            if (cancellationRequests.contains(runId)) return false;
            RunningRun running = runningRuns.get(runId);
            if (running != null && running.thread() == Thread.currentThread()) {
                runningRuns.remove(runId, running);
            }
            return true;
        }
    }

    public void unregister(String runId) {
        if (!StringUtils.hasText(runId)) return;
        synchronized (lifecycleMonitor) {
            RunningRun running = runningRuns.get(runId);
            if (running != null && running.thread() == Thread.currentThread()) {
                runningRuns.remove(runId, running);
            }
            cancellationRequests.remove(runId);
        }
    }

    private record RunningRun(Thread thread, String sessionId) {}
}
