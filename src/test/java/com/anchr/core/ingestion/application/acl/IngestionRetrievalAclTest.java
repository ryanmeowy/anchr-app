package com.anchr.core.ingestion.application.acl;

import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.application.model.IngestionIndexSegment;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.search.application.api.RetrievalGenerationIndexApi;
import com.anchr.core.search.application.api.model.RetrievalGenerationIndexRequest;
import com.anchr.core.search.application.api.model.RetrievalGenerationWriteReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionRetrievalAclTest {

    @Mock private RetrievalGenerationIndexApi api;

    @Test
    void replaceGeneration_shouldTranslateTheWholeGenerationSnapshot() {
        IngestionRetrievalAcl acl = new IngestionRetrievalAcl(api);
        RetrievalGenerationWriteReceipt receipt = new RetrievalGenerationWriteReceipt(
                "kb-1", "asset-1", 2L, 1, "index", "profile");
        when(api.replaceGeneration(org.mockito.ArgumentMatchers.any())).thenReturn(receipt);
        IngestionIndexSegment source = segment("asset-1", 2L);

        assertThat(acl.replaceGeneration(item(), asset("kb-1", "PDF"), List.of(source)))
                .isEqualTo(receipt);

        ArgumentCaptor<RetrievalGenerationIndexRequest> request =
                ArgumentCaptor.forClass(RetrievalGenerationIndexRequest.class);
        verify(api).replaceGeneration(request.capture());
        assertThat(request.getValue().kbId()).isEqualTo("kb-1");
        assertThat(request.getValue().assetId()).isEqualTo("asset-1");
        assertThat(request.getValue().generation()).isEqualTo(2L);
        assertThat(request.getValue().segments()).singleElement().satisfies(value -> {
            assertThat(value.segmentId()).isEqualTo("segment-1");
            assertThat(value.segmentType()).isEqualTo("TEXT_CHUNK");
            assertThat(value.embedding()).containsExactly(0.1f, 0.2f);
        });
    }

    @Test
    void replaceGeneration_shouldRejectSegmentFromAnotherGeneration() {
        IngestionRetrievalAcl acl = new IngestionRetrievalAcl(api);

        assertThatThrownBy(() -> acl.replaceGeneration(
                item(), asset("kb-1", "PDF"), List.of(segment("asset-1", 3L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("generation");

        verify(api, never()).replaceGeneration(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void replaceGeneration_shouldRejectEmptyNonImageSnapshot() {
        IngestionRetrievalAcl acl = new IngestionRetrievalAcl(api);

        assertThatThrownBy(() -> acl.replaceGeneration(
                item(), asset("kb-1", "PDF"), List.of()))
                .isInstanceOf(BusinessException.class);

        verify(api, never()).replaceGeneration(org.mockito.ArgumentMatchers.any());
    }

    private IngestionTaskItem item() {
        return IngestionTaskItem.builder()
                .id("item-1").taskId("task-1").kbId("kb-1")
                .assetId("asset-1").targetIndexGeneration(2L)
                .build();
    }

    private Asset asset(String kbId, String fileType) {
        return Asset.builder().id("asset-1").kbId(kbId).fileType(fileType).build();
    }

    private IngestionIndexSegment segment(String assetId, long generation) {
        return new IngestionIndexSegment(
                "segment-1", "kb-1", assetId, generation, "PDF", "TEXT_CHUNK",
                "title", "content", null, 1, 0, null, null, null,
                List.of(0.1f, 0.2f), "source", null, null, List.of("tag"), 1L);
    }
}
