package com.anchr.core.settings.application.acl;

import com.anchr.core.search.application.api.RetrievalEmbeddingDeploymentApi;
import com.anchr.core.search.application.api.model.RetrievalEmbeddingDeploymentRequest;
import com.anchr.core.settings.application.model.CapabilityEmbeddingProfileSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityRetrievalAclTest {

    @Test
    void mapsCapabilitySnapshotToRetrievalPublishedLanguage() {
        RetrievalEmbeddingDeploymentApi api = mock(RetrievalEmbeddingDeploymentApi.class);
        when(api.requestDeployment(ArgumentMatchers.any())).thenReturn("task-1");
        CapabilityRetrievalAcl acl = new CapabilityRetrievalAcl(api);

        String taskId = acl.requestDeployment(new CapabilityEmbeddingProfileSnapshot(
                42L, "EMBEDDING", "model-a", 1024, "fingerprint-a"));

        ArgumentCaptor<RetrievalEmbeddingDeploymentRequest> captor =
                ArgumentCaptor.forClass(RetrievalEmbeddingDeploymentRequest.class);
        verify(api).requestDeployment(captor.capture());
        assertThat(taskId).isEqualTo("task-1");
        assertThat(captor.getValue()).isEqualTo(new RetrievalEmbeddingDeploymentRequest(
                42L, "EMBEDDING", "model-a", 1024, "fingerprint-a"));
    }
}
