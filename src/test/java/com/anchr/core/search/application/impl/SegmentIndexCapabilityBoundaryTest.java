package com.anchr.core.search.application.impl;

import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.acl.RetrievalCapabilityAcl;
import com.anchr.core.search.application.api.model.RetrievalEmbeddingDeploymentRequest;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class SegmentIndexCapabilityBoundaryTest {

    @Test
    void deploymentApiMapsPublishedRequestToPendingRetrievalProfile() {
        SegmentIndexAliasManager aliasManager = mock(SegmentIndexAliasManager.class);
        SegmentIndexManagerImpl manager = manager(aliasManager);
        manager.markReadyFromStatus(SegmentIndexStatusDTO.builder()
                .indexExists(true)
                .readable(true)
                .writable(true)
                .actualDim(768)
                .actualModel("old-model")
                .actualProfileFingerprint("old-fingerprint")
                .build());

        String taskId = manager.requestDeployment(new RetrievalEmbeddingDeploymentRequest(
                42L, "EMBEDDING", "new-model", 1024, "new-fingerprint"));

        assertThat(taskId).isNotBlank();
        SegmentIndexStatusDTO status = manager.status();
        assertThat(status.getExpectedProfileFingerprint()).isEqualTo("new-fingerprint");
        assertThat(status.getPendingRebuild().getExpectedDim()).isEqualTo(1024);
    }

    @Test
    void successfulAliasSwitchActivatesCapabilityAfterTheSwitch() throws Exception {
        SegmentIndexAliasManager aliasManager = mock(SegmentIndexAliasManager.class);
        RetrievalCapabilityAcl capabilityAcl = mock(RetrievalCapabilityAcl.class);
        SegmentIndexManagerImpl manager = manager(aliasManager);
        manager.setRetrievalCapabilityAcl(capabilityAcl);
        EmbeddingProfile profile =
                new EmbeddingProfile(42L, "EMBEDDING", "new-model", 1024, "fingerprint");

        manager.switchAliasesAndActivate("old-index", "new-index", profile);

        var ordered = inOrder(aliasManager, capabilityAcl);
        ordered.verify(aliasManager).switchAliases("old-index", "new-index");
        ordered.verify(capabilityAcl).activateServingProfile(profile);
    }

    @Test
    void activationFailureSwitchesAliasesBackAndKeepsOldServingPair() throws Exception {
        SegmentIndexAliasManager aliasManager = mock(SegmentIndexAliasManager.class);
        RetrievalCapabilityAcl capabilityAcl = mock(RetrievalCapabilityAcl.class);
        SegmentIndexManagerImpl manager = manager(aliasManager);
        manager.setRetrievalCapabilityAcl(capabilityAcl);
        EmbeddingProfile profile =
                new EmbeddingProfile(42L, "EMBEDDING", "new-model", 1024, "fingerprint");
        doThrow(new IllegalStateException("activation failed"))
                .when(capabilityAcl).activateServingProfile(profile);

        assertThatThrownBy(() ->
                manager.switchAliasesAndActivate("old-index", "new-index", profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Embedding config activation failed after alias switch")
                .hasRootCauseMessage("activation failed");

        var ordered = inOrder(aliasManager, capabilityAcl);
        ordered.verify(aliasManager).switchAliases("old-index", "new-index");
        ordered.verify(capabilityAcl).activateServingProfile(profile);
        ordered.verify(aliasManager).switchAliases("new-index", "old-index");
    }

    private SegmentIndexManagerImpl manager(SegmentIndexAliasManager aliasManager) {
        SegmentIndexConfig config = new SegmentIndexConfig();
        config.setReadAlias("kb_segment_read");
        config.setWriteAlias("kb_segment_write");
        return new SegmentIndexManagerImpl(
                null,
                config,
                () -> Optional.of(new EmbeddingProfile(
                        1L, "EMBEDDING", "old-model", 768, "old-fingerprint")),
                null,
                null,
                null,
                Runnable::run,
                new SegmentIndexWriteBarrier(),
                aliasManager);
    }
}
