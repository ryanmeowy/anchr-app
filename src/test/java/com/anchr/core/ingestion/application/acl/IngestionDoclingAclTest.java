package com.anchr.core.ingestion.application.acl;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.ingestion.application.model.IngestionDoclingException;
import com.anchr.core.ingestion.application.model.IngestionDoclingFailureKind;
import com.anchr.core.integration.ai.client.DoclingClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionDoclingAclTest {

    private final DoclingClient doclingClient = mock(DoclingClient.class);
    private final IngestionDoclingAcl acl = new IngestionDoclingAcl(doclingClient);

    @Test
    void shouldMapJobAndErrorIntoIngestionRecords() {
        ParseRequest request = ParseRequest.builder()
                .requestId("request-1")
                .build();
        when(doclingClient.submitJob(request)).thenReturn(
                new DoclingClient.DoclingJob(
                        "job-1", "request-1", "FAILED", null,
                        new DoclingClient.DoclingJobError(
                                "INTERNAL_ERROR", "retry")));

        var job = acl.submitJob(request);

        assertThat(job.jobId()).isEqualTo("job-1");
        assertThat(job.normalizedStatus()).isEqualTo("failed");
        assertThat(job.error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(job.error().message()).isEqualTo("retry");
    }

    @Test
    void shouldPreserveFailureKindStatusAndRetryAfter() throws Exception {
        when(doclingClient.getJob("job-1", "request-1"))
                .thenThrow(clientFailure(
                        DoclingClient.FailureKind.TRANSIENT,
                        429,
                        Duration.ofSeconds(17),
                        "rate limited"));

        assertThatThrownBy(() -> acl.getJob("job-1", "request-1"))
                .isInstanceOfSatisfying(IngestionDoclingException.class, error -> {
                    assertThat(error.kind())
                            .isEqualTo(IngestionDoclingFailureKind.TRANSIENT);
                    assertThat(error.statusCode()).isEqualTo(429);
                    assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(17));
                    assertThat(error.getMessage()).isEqualTo("rate limited");
                });
    }

    @Test
    void shouldDelegateAckWithoutChangingOrderOrSemantics() {
        acl.ackJob("job-1");

        verify(doclingClient).ackJob("job-1");
    }

    private DoclingClient.DoclingClientException clientFailure(
            DoclingClient.FailureKind kind,
            Integer statusCode,
            Duration retryAfter,
            String message
    ) throws Exception {
        Constructor<DoclingClient.DoclingClientException> constructor =
                DoclingClient.DoclingClientException.class.getDeclaredConstructor(
                        DoclingClient.FailureKind.class,
                        Integer.class,
                        Duration.class,
                        String.class,
                        Throwable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                kind, statusCode, retryAfter, message, null);
    }
}
