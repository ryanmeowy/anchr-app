package com.anchr.core.conversation.infrastructure.persistence;

import com.anchr.core.conversation.domain.model.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTaskRepositoryImplTest {

    @Mock
    private AgentTaskMapper mapper;

    @Test
    void findByIds_shouldUseOneDeduplicatedBatchQuery() {
        AgentTaskRecord record = new AgentTaskRecord();
        record.setTaskId("task_1");
        record.setStatus("RUNNING");
        record.setProgress(35);
        record.setCurrentStage("MAP_SUMMARY");
        when(mapper.findByIds(List.of("task_1"))).thenReturn(List.of(record));

        List<AgentTask> tasks = new AgentTaskRepositoryImpl(mapper)
                .findByIds(List.of("task_1", "task_1"));

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.getTaskId()).isEqualTo("task_1");
            assertThat(task.getProgress()).isEqualTo(35);
        });
        verify(mapper).findByIds(List.of("task_1"));
    }
}
