package com.anchr.core.conversation.application.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskProcessorTimeoutTest {

    @Test
    void shouldUseConfiguredTaskModelTimeoutWhenDeadlineHasEnoughTime() {
        Duration timeout = AgentTaskProcessor.boundedTaskModelTimeout(
                Duration.ofSeconds(90), 200_000, 10_000);

        assertThat(timeout).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void shouldNeverExceedRemainingTaskDeadline() {
        Duration timeout = AgentTaskProcessor.boundedTaskModelTimeout(
                Duration.ofSeconds(90), 25_000, 10_000);

        assertThat(timeout).isEqualTo(Duration.ofSeconds(15));
    }
}
