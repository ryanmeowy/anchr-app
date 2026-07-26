package com.anchr.core.search.infrastructure.persistence;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingDeploymentRepositoryImplTest {

    @Test
    void activateShouldAdvancePreviousServingIndexToFinalCutoverRevision() {
        EmbeddingDeploymentMapper mapper = mock(EmbeddingDeploymentMapper.class);
        EmbeddingDeploymentRepositoryImpl repository =
                new EmbeddingDeploymentRepositoryImpl(mapper);
        EmbeddingProfile profile = new EmbeddingProfile(
                42L, "EMBEDDING", "text-v2", 768, "fingerprint-v2");
        when(mapper.activate("task-1", "owner-1", "kb_segment_v2", 91L))
                .thenReturn(1);

        boolean activated = repository.activate(
                "task-1", "owner-1", profile, "kb_segment_v2", 91L);

        assertThat(activated).isTrue();
        InOrder order = inOrder(mapper);
        order.verify(mapper).activate(
                "task-1", "owner-1", "kb_segment_v2", 91L);
        order.verify(mapper).markOtherPhysicalProfilesRollback(
                "kb_segment_v2", 91L);
        order.verify(mapper).upsertPhysicalProfile(
                "kb_segment_v2", 42L, "fingerprint-v2", "EMBEDDING",
                "text-v2", 768, 91L, "ACTIVE");
    }
}
