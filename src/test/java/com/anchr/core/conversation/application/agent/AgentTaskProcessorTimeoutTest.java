package com.anchr.core.conversation.application.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskProcessorTimeoutTest {

    @Test
    void unwrapMarkdownFence_shouldRemoveWholeAnswerFence() {
        assertThat(AgentTaskProcessor.unwrapMarkdownFence("```markdown\n**标题**\n\n- 内容\n```"))
                .isEqualTo("**标题**\n\n- 内容");
        assertThat(AgentTaskProcessor.unwrapMarkdownFence("```md\n# 标题\n```"))
                .isEqualTo("# 标题");
        assertThat(AgentTaskProcessor.unwrapMarkdownFence("```\n# 标题\n```"))
                .isEqualTo("# 标题");
        assertThat(AgentTaskProcessor.unwrapMarkdownFence("``` Markdown\n# 标题\n```"))
                .isEqualTo("# 标题");
    }

    @Test
    void unwrapMarkdownFence_shouldKeepOrdinaryMarkdown() {
        assertThat(AgentTaskProcessor.unwrapMarkdownFence("**标题**\n\n正文"))
                .isEqualTo("**标题**\n\n正文");
    }

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

    @Test
    void citationDensity_shouldAllowAtMostThreeUniqueReferencesPerParagraph() {
        assertThat(AgentTaskProcessor.citationDensityWithinLimits("""
                第一段 {{segment:s1}} {{segment:s2}} {{segment:s3}}

                第二段 {{segment:s4}} {{segment:s5}}
                """)).isTrue();

        assertThat(AgentTaskProcessor.citationDensityWithinLimits(
                "第一段 {{segment:s1}} {{segment:s2}} {{segment:s3}} {{segment:s4}}"))
                .isFalse();
        assertThat(AgentTaskProcessor.citationDensityWithinLimits(
                "第一段 {{segment:s1}} {{segment:s1}} {{segment:s1}} {{segment:s1}}"))
                .isFalse();
        assertThat(AgentTaskProcessor.citationDensityWithinLimits("""
                - 要点一 {{segment:s1}}
                - 要点二 {{segment:s2}}
                - 要点三 {{segment:s3}}
                - 要点四 {{segment:s4}}
                """)).isTrue();
    }

    @Test
    void citationDensity_shouldRejectMoreThanTenUniqueReferencesOverall() {
        String answer = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> "段落 " + index + " {{segment:s" + index + "}}")
                .collect(java.util.stream.Collectors.joining("\n\n"));

        assertThat(AgentTaskProcessor.citationDensityWithinLimits(answer)).isFalse();
    }
}
