package com.anchr.core.search.application.acl;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.settings.application.api.CapabilityServingConfigApi;
import com.anchr.core.settings.application.api.model.CapabilityServingConfigActivation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RetrievalCapabilityAclTest {

    @Test
    void mapsRetrievalProfileToCapabilityActivationCommand() {
        CapabilityServingConfigApi api = mock(CapabilityServingConfigApi.class);
        RetrievalCapabilityAcl acl = new RetrievalCapabilityAcl(api);

        acl.activateServingProfile(new EmbeddingProfile(
                42L, "MULTI_EMBEDDING", "model-a", 1024, "fingerprint-a"));

        ArgumentCaptor<CapabilityServingConfigActivation> captor =
                ArgumentCaptor.forClass(CapabilityServingConfigActivation.class);
        verify(api).activate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new CapabilityServingConfigActivation(42L, "MULTI_EMBEDDING"));
    }
}
