package com.anchr.core.activity.interfaces.rest;

import com.anchr.core.activity.application.api.ActivityQueryApi;
import com.anchr.core.activity.application.api.model.ActivityQueryResult;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.activity.interfaces.rest.assembler.ActivityRestAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActivityControllerContractTest {

    @Test
    void keepsAllExistingPathsAndJsonShapes() throws Exception {
        ActivityQueryApi queryApi = mock(ActivityQueryApi.class);
        LocalDateTime at = LocalDateTime.of(2026, 7, 29, 12, 30);
        when(queryApi.recentQuestions(null, null)).thenReturn(new ActivityQueryResult.Page<>(List.of(
                new ActivityQueryResult.Question("turn-1", "session-1", "question",
                        List.of("kb-1"), List.of("Knowledge"), at)), "q-next"));
        when(queryApi.recentCitations(null, null)).thenReturn(new ActivityQueryResult.Page<>(List.of(
                new ActivityQueryResult.Citation("record-1", "seg-1", "asset-1", "kb-1", "Knowledge",
                        "guide.pdf", "title", "snippet", "reason", at, "ASK", "turn-1",
                        "session-1", "1", "question", null, List.of())), "c-next"));
        when(queryApi.recentSearch(null, null)).thenReturn(new ActivityQueryResult.Page<>(List.of(
                new ActivityQueryResult.Search("query", List.of("kb-1"), List.of("Knowledge"), 3, at,
                        List.of("PDF"), new ActivityRecordCommand.DateRange(1L, 2L), true, "STRICT")), null));
        when(queryApi.recentDocument(null, null)).thenReturn(new ActivityQueryResult.Page<>(List.of(
                new ActivityQueryResult.Document("task-1", "kb-1", "Knowledge", "RUNNING",
                        4, 1, 1, 2, at)), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ActivityController(queryApi, new ActivityRestAssembler())).build();

        mvc.perform(get("/api/v1/activity/recent-questions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].turnId").value("turn-1"))
                .andExpect(jsonPath("$.data.items[0].knowledgeBaseNames[0]").value("Knowledge"))
                .andExpect(jsonPath("$.data.nextCursor").value("q-next"));
        mvc.perform(get("/api/v1/activity/recent-citations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].recordId").value("record-1"))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("ASK"))
                .andExpect(jsonPath("$.data.nextCursor").value("c-next"));
        mvc.perform(get("/api/v1/activity/recent-search"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].query").value("query"))
                .andExpect(jsonPath("$.data.items[0].dateRange.from").value(1))
                .andExpect(jsonPath("$.data.items[0].withAnswer").value(true));
        mvc.perform(get("/api/v1/activity/recent-document"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.data.items[0].runningCount").value(2));
    }
}
