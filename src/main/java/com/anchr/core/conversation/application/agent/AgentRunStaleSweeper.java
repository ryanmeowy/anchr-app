package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fails Agent runs that have been waiting for turn persistence for too long.
 *
 * <p>{@code AWAITING_TURN} is normally a short hand-off state between the workflow and the
 * conversation turn transaction. A crash or an unexpected exception in that window must not
 * leave the run recoverable forever.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunStaleSweeper {

    static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);
    static final int BATCH_LIMIT = 100;
    private static final long SWEEP_INTERVAL_MILLIS = 300_000L;

    private final AgentTraceRepository repository;

    @Scheduled(
            fixedDelay = SWEEP_INTERVAL_MILLIS,
            initialDelay = SWEEP_INTERVAL_MILLIS
    )
    public void sweep() {
        try {
            int failed = repository.failStaleAwaitingRuns(
                    (int) STALE_THRESHOLD.toMinutes(), BATCH_LIMIT);
            if (failed > 0) {
                log.warn("Marked {} stale AWAITING_TURN Agent run(s) as FAILED", failed);
            }
        } catch (RuntimeException exception) {
            log.warn("Agent run stale sweep failed", exception);
        }
    }
}
