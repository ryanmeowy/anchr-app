package com.anchr.core.ingestion.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageHashStatePolicyTest {

    @Test
    void evaluateAcquireResult_shouldAllow_whenLockAcquired() {
        ImageHashAcquireOutcome outcome = ImageHashStatePolicy.evaluateAcquireResult(true, null);

        assertThat(outcome.type()).isEqualTo(ImageHashAcquireOutcomeType.ALLOW);
    }

    @Test
    void evaluateAcquireResult_shouldRejectDuplicate_whenStatusIsSuccess() {
        ImageHashAcquireOutcome outcome = ImageHashStatePolicy.evaluateAcquireResult(false, "SUCCESS");

        assertThat(outcome.type()).isEqualTo(ImageHashAcquireOutcomeType.REJECT_DUPLICATE);
        assertThat(outcome.message()).isEqualTo("Duplicate image, skipped.");
    }

    @Test
    void evaluateAcquireResult_shouldRetryFromFailed_whenStatusIsFailed() {
        ImageHashAcquireOutcome outcome = ImageHashStatePolicy.evaluateAcquireResult(false, "FAILED");

        assertThat(outcome.type()).isEqualTo(ImageHashAcquireOutcomeType.RETRY_FROM_FAILED);
    }

    @Test
    void evaluateAcquireResult_shouldRejectProcessing_whenStatusIsUnknown() {
        ImageHashAcquireOutcome outcome = ImageHashStatePolicy.evaluateAcquireResult(false, "PROCESSING");

        assertThat(outcome.type()).isEqualTo(ImageHashAcquireOutcomeType.REJECT_PROCESSING);
        assertThat(outcome.message()).isEqualTo("Image is processing, retry later.");
    }
}

