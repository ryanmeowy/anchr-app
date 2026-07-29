package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import com.anchr.core.search.domain.model.SegmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchFollowUpServiceImplTest {

    @Test
    void visualOnlyResultShouldNotCallTheQuestionModel() {
        AtomicInteger calls = new AtomicInteger();
        SearchFollowUpServiceImpl service = new SearchFollowUpServiceImpl(
                prompt -> {
                    calls.incrementAndGet();
                    return "[\"q1\"]";
                },
                new ObjectMapper(),
                new SimpleMeterRegistry());
        RetrievalHit visual = hit(SegmentType.IMAGE_VISUAL.name(), "image title", List.of());

        assertThat(service.generate("question", List.of(visual))).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void visualPrimaryShouldStillUseOcrChunkAsFollowUpContext() {
        AtomicReference<String> prompt = new AtomicReference<>();
        SearchFollowUpServiceImpl service = new SearchFollowUpServiceImpl(
                value -> {
                    prompt.set(value);
                    return "[\"q1\"]";
                },
                new ObjectMapper(),
                new SimpleMeterRegistry());
        RetrievalHit result = hit(SegmentType.IMAGE_VISUAL.name(), "", List.of(
                chunk(SegmentType.IMAGE_VISUAL.name(), "", null),
                chunk(SegmentType.IMAGE_OCR_BLOCK.name(), "recognized database diagram", 0.8D)));

        assertThat(service.generate("question", List.of(result)))
                .containsExactly("q1");
        assertThat(prompt.get())
                .contains("recognized database diagram")
                .doesNotContain("image title");
    }

    private RetrievalHit hit(String segmentType, String snippet, List<RetrievalTopChunk> chunks) {
        return new RetrievalHit(segmentType, null, null, null, null, snippet, null, null,
                null, null, null, null, null, chunks, null, null, null, null, null, null);
    }

    private RetrievalTopChunk chunk(String segmentType, String snippet, Double score) {
        return new RetrievalTopChunk(null, null, segmentType, null, null, snippet, null, score,
                null, null, null, null, null, null, null);
    }
}
