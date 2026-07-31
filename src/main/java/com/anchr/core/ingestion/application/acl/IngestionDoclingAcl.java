package com.anchr.core.ingestion.application.acl;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.ingestion.application.model.IngestionDoclingException;
import com.anchr.core.ingestion.application.model.IngestionDoclingFailureKind;
import com.anchr.core.ingestion.application.model.IngestionDoclingJob;
import com.anchr.core.ingestion.application.model.IngestionDoclingJobError;
import com.anchr.core.integration.ai.client.DoclingClient;
import com.anchr.core.integration.ai.client.DoclingClient.DoclingClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Ingestion-side anti-corruption layer for the Docling HTTP client. */
@Component
@RequiredArgsConstructor
public class IngestionDoclingAcl {

    private final DoclingClient doclingClient;

    public IngestionDoclingJob submitJob(ParseRequest request) {
        try {
            return map(doclingClient.submitJob(request));
        } catch (DoclingClientException failure) {
            throw map(failure);
        }
    }

    public IngestionDoclingJob submitJob(ParseRequest request, int maxResponseBytes) {
        try {
            return map(doclingClient.submitJob(request, maxResponseBytes));
        } catch (DoclingClientException failure) {
            throw map(failure);
        }
    }

    public IngestionDoclingJob getJob(String jobId, String expectedRequestId) {
        try {
            return map(doclingClient.getJob(jobId, expectedRequestId));
        } catch (DoclingClientException failure) {
            throw map(failure);
        }
    }

    public IngestionDoclingJob getJob(
            String jobId, String expectedRequestId, int maxResponseBytes) {
        try {
            return map(doclingClient.getJob(jobId, expectedRequestId, maxResponseBytes));
        } catch (DoclingClientException failure) {
            throw map(failure);
        }
    }

    public void ackJob(String jobId) {
        try {
            doclingClient.ackJob(jobId);
        } catch (DoclingClientException failure) {
            throw map(failure);
        }
    }

    private IngestionDoclingJob map(DoclingClient.DoclingJob job) {
        IngestionDoclingJobError error = job.error() == null
                ? null
                : new IngestionDoclingJobError(
                        job.error().code(), job.error().message());
        return new IngestionDoclingJob(
                job.jobId(),
                job.requestId(),
                job.status(),
                job.result(),
                error);
    }

    private IngestionDoclingException map(DoclingClientException failure) {
        return new IngestionDoclingException(
                IngestionDoclingFailureKind.valueOf(failure.kind().name()),
                failure.statusCode(),
                failure.retryAfter(),
                failure.getMessage(),
                failure);
    }
}
