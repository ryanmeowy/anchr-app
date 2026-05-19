package com.smart.vision.core.ingestion.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.ingestion.application.assembler.BatchTaskAssembler;
import com.smart.vision.core.ingestion.domain.repository.ImageHashStateRepository;
import com.smart.vision.core.ingestion.domain.port.IngestionContentPort;
import com.smart.vision.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.smart.vision.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.smart.vision.core.ingestion.domain.port.IngestionOcrPort;
import com.smart.vision.core.ingestion.infrastructure.persistence.es.EsBatchTemplate;
import com.smart.vision.core.ingestion.infrastructure.persistence.es.ImageSegmentIndexWriter;
import com.smart.vision.core.common.util.IdGen;
import com.smart.vision.core.ingestion.interfaces.rest.dto.BatchTaskStatusDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageIngestionServiceImplRetryFlowTest {

    @Mock
    private EsBatchTemplate esBatchTemplate;
    @Mock
    private IngestionObjectStoragePort objectStoragePort;
    @Mock
    private IngestionEmbeddingPort embeddingPort;
    @Mock
    private IngestionOcrPort ocrPort;
    @Mock
    private IngestionContentPort contentPort;
    @Mock
    private ImageHashStateRepository imageHashStateRepository;
    @Mock
    private ImageSegmentIndexWriter imageSegmentIndexWriter;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private IdGen idGen;
    @Mock
    private ValueOperations<String, String> valueOps;

    private Executor directExecutor;
    private ObjectMapper objectMapper;
    private ImageIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        directExecutor = Runnable::run;
        objectMapper = new ObjectMapper();
        service = new ImageIngestionServiceImpl(
                esBatchTemplate,
                directExecutor,
                directExecutor,
                objectStoragePort,
                embeddingPort,
                ocrPort,
                contentPort,
                imageSegmentIndexWriter,
                imageHashStateRepository,
                new BatchTaskAssembler(),
                redisTemplate,
                idGen,
                objectMapper
        );
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void retryBatchTaskItem_shouldResetFailedItemToPendingAndIncreaseRetryCount() throws Exception {
        BatchTaskStatusDTO task = buildTask("task-1", "item-1", "FAILED");
        String taskKey = "img:batch:task:" + task.getTaskId();
        when(valueOps.get(taskKey)).thenReturn(objectMapper.writeValueAsString(task));
        when(valueOps.setIfAbsent(anyString(), anyString(), eq(600L), eq(TimeUnit.SECONDS))).thenReturn(false);

        BatchTaskStatusDTO updated = service.retryBatchTaskItem("task-1", "item-1");

        BatchTaskStatusDTO.ItemStatus item = updated.getItems().getFirst();
        assertThat(item.getStatus()).isEqualTo("PENDING");
        assertThat(item.getRetryCount()).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo("PENDING");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(taskKey), payloadCaptor.capture(), eq(24L), eq(TimeUnit.HOURS));
        BatchTaskStatusDTO saved = objectMapper.readValue(payloadCaptor.getValue(), BatchTaskStatusDTO.class);
        assertThat(saved.getItems().getFirst().getStatus()).isEqualTo("PENDING");
        assertThat(saved.getItems().getFirst().getRetryCount()).isEqualTo(1);
    }

    @Test
    void retryAllFailedBatchTaskItems_shouldThrowWhenNoFailedItems() throws Exception {
        BatchTaskStatusDTO task = buildTask("task-2", "item-1", "SUCCESS");
        String taskKey = "img:batch:task:" + task.getTaskId();
        when(valueOps.get(taskKey)).thenReturn(objectMapper.writeValueAsString(task));

        assertThatThrownBy(() -> service.retryAllFailedBatchTaskItems("task-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No FAILED items to retry");

        verify(valueOps, never()).set(eq(taskKey), anyString(), eq(24L), eq(TimeUnit.HOURS));
    }

    private BatchTaskStatusDTO buildTask(String taskId, String itemId, String itemStatus) {
        long now = System.currentTimeMillis();
        BatchTaskStatusDTO task = new BatchTaskStatusDTO();
        task.setTaskId(taskId);
        task.setStatus("PARTIAL_FAILED");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        BatchTaskStatusDTO.ItemStatus item = new BatchTaskStatusDTO.ItemStatus();
        item.setItemId(itemId);
        item.setKey("k");
        item.setFileName("f.png");
        item.setFileHash("h");
        item.setStatus(itemStatus);
        item.setRetryCount(0);
        item.setUpdatedAt(now);
        task.setItems(List.of(item));
        task.setTotal(1);
        task.setFailureCount("FAILED".equals(itemStatus) ? 1 : 0);
        task.setSuccessCount("SUCCESS".equals(itemStatus) ? 1 : 0);
        task.setPendingCount(0);
        task.setRunningCount(0);
        return task;
    }
}
