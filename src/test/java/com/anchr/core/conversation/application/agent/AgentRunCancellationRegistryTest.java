package com.anchr.core.conversation.application.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunCancellationRegistryTest {

    @Test
    void cancel_shouldInterruptRegisteredRun() throws Exception {
        AgentRunCancellationRegistry registry = new AgentRunCancellationRegistry();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            registry.register("run-1", "session-1");
            registered.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            } finally {
                registry.unregister("run-1");
            }
        });
        worker.start();
        assertThat(registered.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.cancel("run-1")).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        worker.join(1_000);
    }

    @Test
    void cancelBySessionId_shouldInterruptOnlyMatchingRuns() throws Exception {
        AgentRunCancellationRegistry registry = new AgentRunCancellationRegistry();
        CountDownLatch registered = new CountDownLatch(2);
        CountDownLatch matchingInterrupted = new CountDownLatch(1);
        CountDownLatch otherInterrupted = new CountDownLatch(1);
        Thread matching = worker(registry, "run-1", "session-1", registered, matchingInterrupted);
        Thread other = worker(registry, "run-2", "session-2", registered, otherInterrupted);
        matching.start();
        other.start();
        assertThat(registered.await(1, TimeUnit.SECONDS)).isTrue();

        registry.cancelBySessionId("session-1");

        assertThat(matchingInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(otherInterrupted.await(100, TimeUnit.MILLISECONDS)).isFalse();
        other.interrupt();
        matching.join(1_000);
        other.join(1_000);
    }

    @Test
    void terminalClaim_shouldLoseWhenCancellationWasAlreadyAccepted() {
        AgentRunCancellationRegistry registry = new AgentRunCancellationRegistry();
        registry.register("run-1", "session-1");

        try {
            assertThat(registry.cancel("run-1")).isTrue();
            assertThat(registry.tryClaimTerminal("run-1")).isFalse();
        } finally {
            registry.unregister("run-1");
            Thread.interrupted();
        }
    }

    @Test
    void terminalClaim_shouldMakeLaterCancellationLose() {
        AgentRunCancellationRegistry registry = new AgentRunCancellationRegistry();
        registry.register("run-1", "session-1");

        assertThat(registry.tryClaimTerminal("run-1")).isTrue();
        assertThat(registry.cancel("run-1")).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        registry.unregister("run-1");
    }

    private Thread worker(AgentRunCancellationRegistry registry,
                          String runId,
                          String sessionId,
                          CountDownLatch registered,
                          CountDownLatch interrupted) {
        return new Thread(() -> {
            registry.register(runId, sessionId);
            registered.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            } finally {
                registry.unregister(runId);
            }
        });
    }
}
