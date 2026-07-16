package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentBudget;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.agent.AgentToolException;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeGuardTest {
    private final AssetRepository repository = mock(AssetRepository.class);
    private final AgentScopeGuard guard = new AgentScopeGuard(repository);

    @Test
    void shouldResolveUniqueFileNameInsideAuthorizedKnowledgeBases() {
        Asset asset = asset("asset-1", "kb-1", "经验报告.pdf", "经验报告");
        when(repository.findActiveById("kb-1", "经验报告.pdf")).thenReturn(Optional.empty());
        when(repository.listActive("kb-1", "经验报告.pdf", null, 50, 0)).thenReturn(List.of(asset));

        Asset resolved = guard.requireAsset("《经验报告.pdf》", context(List.of()));

        assertThat(resolved.getId()).isEqualTo("asset-1");
    }

    @Test
    void shouldReportDocumentNotFoundInsteadOfPermissionWhenReferenceIsInvalid() {
        when(repository.findActiveById("kb-1", "segment-123")).thenReturn(Optional.empty());
        when(repository.listActive("kb-1", "segment-123", null, 50, 0)).thenReturn(List.of());

        assertThatThrownBy(() -> guard.requireAsset("segment-123", context(List.of())))
                .isInstanceOfSatisfying(AgentToolException.class,
                        error -> assertThat(error.getCode()).isEqualTo("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void shouldKeepPermissionDeniedForAssetOutsideExplicitRequestScope() {
        Asset asset = asset("asset-outside", "kb-1", "outside.pdf", "outside");
        when(repository.findActiveById("kb-1", "asset-outside")).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> guard.requireAsset("asset-outside", context(List.of("asset-allowed"))))
                .isInstanceOfSatisfying(AgentToolException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PERMISSION_DENIED"));
    }

    private AgentExecutionContext context(List<String> assets) {
        return new AgentExecutionContext("run", "turn", "session", "user", List.of("kb-1"), assets,
                new AgentBudget(12, 8, System.currentTimeMillis() + 10_000));
    }

    private Asset asset(String id, String kbId, String fileName, String title) {
        return Asset.builder().id(id).kbId(kbId).fileName(fileName).title(title).build();
    }
}
