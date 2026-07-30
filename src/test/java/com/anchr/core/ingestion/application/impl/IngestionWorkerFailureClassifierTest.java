package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionWorkerFailureClassifierTest {
    private final IngestionWorkerFailureClassifier classifier =
            new IngestionWorkerFailureClassifier();

    @Test
    void shouldKeepInterruptedBusinessEmbeddingAndUnexpectedMappings() {
        assertThat(classifier.classify(
                new IngestionWorkerInterruptedException(new InterruptedException())))
                .satisfies(failure -> {
                    assertThat(failure.error()).isEqualTo(ApiError.INTERNAL_ERROR);
                    assertThat(failure.message())
                            .isEqualTo("文档处理线程被中断，请重新执行。");
                });
        assertThat(classifier.classify(
                new BusinessException(ApiError.TEXT_PARSE_FAILED, "parse failed")))
                .satisfies(failure -> {
                    assertThat(failure.error()).isEqualTo(ApiError.TEXT_PARSE_FAILED);
                    assertThat(failure.message()).isEqualTo("parse failed");
                });
        assertThat(classifier.classify(
                new IngestionEmbeddingCallException(
                        new IllegalStateException("provider failed"))))
                .satisfies(failure -> {
                    assertThat(failure.error()).isEqualTo(ApiError.EMBEDDING_FAILED);
                    assertThat(failure.message()).isEqualTo("provider failed");
                });
        assertThat(classifier.classify(new IllegalStateException("unexpected")))
                .satisfies(failure -> {
                    assertThat(failure.error()).isEqualTo(ApiError.INTERNAL_ERROR);
                    assertThat(failure.message()).isEqualTo("unexpected");
                });
    }

    @Test
    void shouldKeepTheExistingOneThousandCharacterErrorLimit() {
        IngestionWorkerFailureClassifier.Failure failure =
                classifier.classify(new IllegalStateException("x".repeat(1_200)));

        assertThat(failure.message()).hasSize(1_000);
    }
}
