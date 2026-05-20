package com.anchr.core.ingestion.domain.model;

/**
 * Decision policy for hash lock fallback path.
 */
public final class ImageHashStatePolicy {

    private ImageHashStatePolicy() {
    }

    public static ImageHashAcquireOutcome evaluateAcquireResult(boolean lockAcquired, String currentStatus) {
        if (lockAcquired) {
            return ImageHashAcquireOutcome.allow();
        }

        ImageHashStatus status = ImageHashStatus.fromValue(currentStatus).orElse(null);
        if (status == ImageHashStatus.SUCCESS) {
            return ImageHashAcquireOutcome.rejectDuplicate();
        }
        if (status == ImageHashStatus.FAILED) {
            return ImageHashAcquireOutcome.retryFromFailed();
        }
        return ImageHashAcquireOutcome.rejectProcessing();
    }
}

