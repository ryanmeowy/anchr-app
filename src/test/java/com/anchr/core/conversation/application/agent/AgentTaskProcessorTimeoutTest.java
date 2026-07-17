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
    void summaryStream_shouldStripOpeningFenceBeforeCompletion() {
        String content = "# 标题\n\n" + "这是流式总结内容。".repeat(20);

        String visible = AgentTaskProcessor.visibleSummaryMarkdown(
                "```markdown\n" + content + "\n```", false);

        assertThat(visible).isNotEmpty();
        assertThat(visible).startsWith("# 标题");
        assertThat(visible).doesNotContain("```markdown");
        assertThat(visible.length()).isLessThan(content.length());
    }

    @Test
    void summaryStream_shouldWaitOnlyForASplitOpeningFenceHeader() {
        assertThat(AgentTaskProcessor.visibleSummaryMarkdown("`", false)).isEmpty();
        assertThat(AgentTaskProcessor.visibleSummaryMarkdown("```mark", false)).isEmpty();
        assertThat(AgentTaskProcessor.visibleSummaryMarkdown("```markdown", false)).isEmpty();

        String content = "正文".repeat(80);
        assertThat(AgentTaskProcessor.visibleSummaryMarkdown("```markdown\n" + content, false))
                .startsWith("正文");
    }

    @Test
    void summaryStream_shouldKeepOrdinaryMarkdownAndRemoveFenceOnCompletion() {
        String content = "# 标题\n" + "普通内容".repeat(40);

        assertThat(AgentTaskProcessor.visibleSummaryMarkdown(content, false)).startsWith("# 标题");
        assertThat(AgentTaskProcessor.visibleSummaryMarkdown(
                "```md\n" + content + "\n```", true)).isEqualTo(content);
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

    @Test
    void citationCompaction_shouldEnforceAllLimitsWithoutRewritingTheAnswer() {
        String answer = "第一段 {{segment:s1}} {{segment:s2}} {{segment:s3}} {{segment:s4}}\n\n"
                + java.util.stream.IntStream.rangeClosed(5, 14)
                .mapToObj(index -> "段落 " + index + " {{segment:s" + index + "}}")
                .collect(java.util.stream.Collectors.joining("\n\n"));

        String compacted = AgentTaskProcessor.compactCitationMarkers(answer);

        assertThat(AgentTaskProcessor.citationDensityWithinLimits(compacted)).isTrue();
        assertThat(AgentCitationRenderer.extractSegmentIds(compacted)).hasSize(10);
        assertThat(compacted).contains("第一段", "段落 14");
        assertThat(compacted).doesNotContain("{{segment:s4}}", "{{segment:s14}}");
    }

    @Test
    void citationCompaction_shouldRemainPrefixStableDuringStreaming() {
        String prefix = "第一段 {{segment:s1}} {{segment:s2}} {{segment:s3}} {{segment:s4}}\n\n第二段 ";
        String complete = prefix + "{{segment:s5}} 后续内容 {{segment:s6}}";

        String compactedPrefix = AgentTaskProcessor.compactCitationMarkers(prefix);
        String compactedComplete = AgentTaskProcessor.compactCitationMarkers(complete);

        assertThat(compactedComplete).startsWith(compactedPrefix);
    }
}
