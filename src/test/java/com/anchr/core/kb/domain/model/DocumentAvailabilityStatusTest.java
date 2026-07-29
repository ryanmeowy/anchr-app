package com.anchr.core.kb.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAvailabilityStatusTest {

    @Test
    void from_shouldKeepExistingIndexAnswerableWhenLatestAttemptFailed() {
        Asset asset = Asset.builder()
                .activeIndexGeneration(3L)
                .parseStatus(DocumentParseStatus.FAILED)
                .indexStatus(DocumentIndexStatus.FAILED)
                .build();

        assertThat(DocumentAvailabilityStatus.from(asset))
                .isEqualTo(DocumentAvailabilityStatus.ANSWERABLE);
    }

    @Test
    void from_shouldClassifyInitialPipelineStates() {
        assertThat(DocumentAvailabilityStatus.from(Asset.builder()
                .parseStatus(DocumentParseStatus.RUNNING)
                .indexStatus(DocumentIndexStatus.PENDING)
                .build())).isEqualTo(DocumentAvailabilityStatus.PROCESSING);
        assertThat(DocumentAvailabilityStatus.from(Asset.builder()
                .parseStatus(DocumentParseStatus.FAILED)
                .indexStatus(DocumentIndexStatus.FAILED)
                .build())).isEqualTo(DocumentAvailabilityStatus.FAILED);
        assertThat(DocumentAvailabilityStatus.from(Asset.builder()
                .parseStatus(DocumentParseStatus.SUCCESS)
                .indexStatus(DocumentIndexStatus.SUCCESS)
                .build())).isEqualTo(DocumentAvailabilityStatus.ANSWERABLE);
    }
}
