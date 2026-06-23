package com.anchr.core.ingestion.interfaces.rest;

import com.anchr.core.common.exception.GlobalExceptionHandler;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseIngestionControllerTest {

    @Mock
    private IngestionApplicationService ingestionApplicationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeBaseIngestionController(ingestionApplicationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createTask_shouldDefaultDedupeStrategyToSkipWhenMissing() throws Exception {
        when(ingestionApplicationService.createTask(eq("kb-1"), any()))
                .thenReturn(task());

        mockMvc.perform(post("/api/v1/kbs/kb-1/ingestion-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "UPLOAD",
                                  "items": [
                                    {
                                      "fileName": "mysql.pdf",
                                      "fileType": "PDF",
                                      "objectKey": "objects/mysql.pdf",
                                      "fileHash": "hash-a"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("task-1"));

        ArgumentCaptor<IngestionApplicationService.IngestionCreateCommand> commandCaptor =
                ArgumentCaptor.forClass(IngestionApplicationService.IngestionCreateCommand.class);
        verify(ingestionApplicationService).createTask(eq("kb-1"), commandCaptor.capture());
        assertThat(commandCaptor.getValue().dedupeStrategy()).isEqualTo(DedupeStrategy.SKIP);
    }

    @Test
    void createTask_shouldReturn400EnvelopeWhenDedupeStrategyInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/kbs/kb-1/ingestion-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dedupeStrategy": "REPLACE",
                                  "items": [
                                    {
                                      "fileName": "mysql.pdf",
                                      "fileType": "PDF",
                                      "objectKey": "objects/mysql.pdf",
                                      "fileHash": "hash-a"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request parameters."));
    }

    private IngestionTask task() {
        LocalDateTime now = LocalDateTime.now();
        return IngestionTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .sourceType(IngestionSourceType.UPLOAD)
                .status(IngestionTaskStatus.PENDING)
                .totalCount(1)
                .createdAt(now)
                .updatedAt(now)
                .items(List.of())
                .build();
    }
}
