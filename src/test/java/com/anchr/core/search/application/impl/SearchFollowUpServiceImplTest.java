package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
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
        SearchResultDTO visual = SearchResultDTO.builder()
                .segmentType(SegmentType.IMAGE_VISUAL.name())
                .snippet("image title")
                .build();

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
        SearchResultDTO result = SearchResultDTO.builder()
                .segmentType(SegmentType.IMAGE_VISUAL.name())
                .snippet("")
                .topChunks(List.of(
                        SearchResultDTO.TopChunk.builder()
                                .segmentType(SegmentType.IMAGE_VISUAL.name())
                                .snippet("")
                                .build(),
                        SearchResultDTO.TopChunk.builder()
                                .segmentType(
                                        SegmentType.IMAGE_OCR_BLOCK.name())
                                .snippet("recognized database diagram")
                                .score(0.8D)
                                .build()))
                .build();

        assertThat(service.generate("question", List.of(result)))
                .containsExactly("q1");
        assertThat(prompt.get())
                .contains("recognized database diagram")
                .doesNotContain("image title");
    }
}
