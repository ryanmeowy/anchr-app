package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestionTaskRepositoryImplTest {

    @Mock
    private IngestionTaskMapper mapper;

    @Test
    void freshReembedProjectionMustStillStartFromParseWithoutAStoredArtifact() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .fileName("document.pdf")
                .stage(IngestionStage.EMBED)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(60)
                .createdAt(now)
                .updatedAt(now)
                .build();
        IngestionTask task = IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .sourceType(IngestionSourceType.REEMBED)
                .status(IngestionTaskStatus.PENDING)
                .totalCount(1)
                .createdBy("user-a")
                .updatedBy("user-a")
                .createdAt(now)
                .updatedAt(now)
                .items(List.of(item))
                .build();

        new IngestionTaskRepositoryImpl(mapper).save(task);

        ArgumentCaptor<IngestionTaskItemRecord> record =
                ArgumentCaptor.forClass(IngestionTaskItemRecord.class);
        verify(mapper).insertItem(record.capture());
        assertThat(record.getValue().getExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_SUBMIT.name());
        assertThat(record.getValue().getParseResultObjectKey()).isNull();
    }

    @Test
    void explicitEmbedStartMustRequireParseArtifact() {
        IngestionTask task = taskWithExplicitStage(
                IngestionExecutionStage.EMBED, null, null);

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse artifact");
    }

    @Test
    void explicitIndexStartMustRequireBothArtifacts() {
        IngestionTask task = taskWithExplicitStage(
                IngestionExecutionStage.INDEX, "parse-result.gz", null);

        assertThatThrownBy(() -> new IngestionTaskRepositoryImpl(mapper).save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse and embedding artifacts");
    }

    private IngestionTask taskWithExplicitStage(IngestionExecutionStage executionStage,
                                                String parseArtifact,
                                                String embeddingArtifact) {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .fileName("document.pdf")
                .executionStage(executionStage)
                .parseResultObjectKey(parseArtifact)
                .embeddingResultObjectKey(embeddingArtifact)
                .stage(executionStage == IngestionExecutionStage.INDEX
                        ? IngestionStage.INDEX : IngestionStage.EMBED)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .sourceType(IngestionSourceType.REEMBED)
                .status(IngestionTaskStatus.PENDING)
                .totalCount(1)
                .createdBy("user-a")
                .updatedBy("user-a")
                .createdAt(now)
                .updatedAt(now)
                .items(List.of(item))
                .build();
    }
}
