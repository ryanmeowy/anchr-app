package com.anchr.core.search.application;

import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.IndexRuntimeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexRuntimeContextTest {

    @Test
    void nestedScopesShouldRestoreThePreviousAtomicSnapshot() {
        IndexRuntimeContext context = new IndexRuntimeContext();
        IndexRuntimeSnapshot first = snapshot("index-a", "profile-a");
        IndexRuntimeSnapshot second = snapshot("index-b", "profile-b");

        String observed = context.withSnapshot(first, () -> {
            assertThat(context.current()).contains(first);
            context.withSnapshot(second, () -> {
                assertThat(context.current()).contains(second);
                return null;
            });
            return context.current().orElseThrow().physicalIndex();
        });

        assertThat(observed).isEqualTo("index-a");
        assertThat(context.current()).isEmpty();
    }

    private IndexRuntimeSnapshot snapshot(String index, String fingerprint) {
        EmbeddingProfile profile =
                new EmbeddingProfile(1L, "EMBEDDING", "model", 2, fingerprint);
        return new IndexRuntimeSnapshot(
                index, profile, (source, type) -> List.of(0.1F, 0.2F),
                IndexRuntimeSnapshot.RetrievalPlan.singleEmbeddingV1());
    }
}
