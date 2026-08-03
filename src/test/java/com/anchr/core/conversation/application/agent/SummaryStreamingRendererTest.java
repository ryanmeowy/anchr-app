package com.anchr.core.conversation.application.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryStreamingRendererTest {

    @Test
    void shouldHoldSplitTokenAndPublishOnlyVisibleCitationLabel() {
        List<String> deltas = new ArrayList<>();
        SummaryStreamingRenderer renderer = renderer(deltas);

        renderer.accept("# 总结\n\n" + "可靠内容。".repeat(20) + "{{ci");
        renderer.accept("te:1}} 后续内容。" + "补充。".repeat(30));

        assertThat(String.join("", deltas))
                .contains("# 总结")
                .contains("[1-1]")
                .doesNotContain("segment-1", "{{cite:");
        assertThat(renderer.finishInternalAnswer())
                .contains("{{segment:segment-1}}")
                .doesNotContain("{{cite:");
    }

    @Test
    void shouldStripWholeAnswerFenceWithoutStreamingFenceHeader() {
        List<String> deltas = new ArrayList<>();
        SummaryStreamingRenderer renderer = renderer(deltas);

        renderer.accept("```markdown\n# 标题\n" + "正文。".repeat(60) + "{{cite:1}}\n```");

        assertThat(String.join("", deltas)).startsWith("# 标题").doesNotContain("```markdown");
        assertThat(renderer.finishInternalAnswer()).startsWith("# 标题").doesNotContain("```");
    }

    @Test
    void shouldRejectRawSegmentIdsAndAuthoredVisibleLabels() {
        assertThatThrownBy(() -> renderer(new ArrayList<>()).accept(
                "正文直接暴露 segment-1 " + "填充。".repeat(40)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> renderer(new ArrayList<>()).accept(
                "正文自行写入 [1-1] " + "填充。".repeat(40)))
                .isInstanceOf(IllegalStateException.class);
    }

    private SummaryStreamingRenderer renderer(List<String> deltas) {
        return new SummaryStreamingRenderer(
                Map.of("{{cite:1}}", "segment-1"),
                Map.of("segment-1", new AgentCitationReference(1, 1, "segment-1")),
                deltas::add);
    }
}
