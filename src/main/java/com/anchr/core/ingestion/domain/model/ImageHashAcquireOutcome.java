package com.anchr.core.ingestion.domain.model;

/**
 * Domain decision for lock acquisition fallback path.
 *
 * @param type    outcome type
 * @param message rejection message when applicable
 */
public record ImageHashAcquireOutcome(ImageHashAcquireOutcomeType type, String message) {

    public static ImageHashAcquireOutcome allow() {
        return new ImageHashAcquireOutcome(ImageHashAcquireOutcomeType.ALLOW, null);
    }

    public static ImageHashAcquireOutcome retryFromFailed() {
        return new ImageHashAcquireOutcome(ImageHashAcquireOutcomeType.RETRY_FROM_FAILED, null);
    }

    public static ImageHashAcquireOutcome rejectDuplicate() {
        return new ImageHashAcquireOutcome(ImageHashAcquireOutcomeType.REJECT_DUPLICATE, "Duplicate image, skipped.");
    }

    public static ImageHashAcquireOutcome rejectProcessing() {
        return new ImageHashAcquireOutcome(ImageHashAcquireOutcomeType.REJECT_PROCESSING, "Image is processing, retry later.");
    }
}

