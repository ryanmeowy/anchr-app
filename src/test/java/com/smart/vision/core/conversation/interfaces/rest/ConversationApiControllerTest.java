package com.smart.vision.core.conversation.interfaces.rest;

import com.smart.vision.core.common.exception.GlobalExceptionHandler;
import com.smart.vision.core.conversation.application.ConversationService;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationCreateRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationMessageResponseDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationRenameRequestDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationSessionDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationSessionListDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ConversationTurnListDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.PreviewAnchorDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ResultCardDTO;
import com.smart.vision.core.conversation.interfaces.rest.dto.ResultHitDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConversationApiControllerTest {

    @Mock
    private ConversationService conversationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ConversationApiController(conversationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createSession_shouldReturnResultEnvelope() throws Exception {
        ConversationSessionDTO session = new ConversationSessionDTO();
        session.setSessionId("cvs_test_001");
        session.setUserId("uk_default");
        session.setStatus("ACTIVE");
        session.setCreatedAt(1777520000000L);
        session.setUpdatedAt(1777520000000L);
        session.setExpiresAt(1780112000000L);
        when(conversationService.createSession(eq("uk_default"), eq(new ConversationCreateRequestDTO()))).thenReturn(session);

        mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("cvs_test_001"))
                .andExpect(jsonPath("$.data.userId").value("uk_default"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void listSessions_shouldReturnSessionPage() throws Exception {
        ConversationSessionDTO item = new ConversationSessionDTO();
        item.setSessionId("cvs_test_001");
        item.setUserId("uk_default");
        item.setTitle("MySQL");
        item.setStatus("ACTIVE");
        item.setLastMessagePreview("InnoDB 是默认事务引擎。");
        item.setCreatedAt(1777520000000L);
        item.setUpdatedAt(1777520001000L);
        item.setExpiresAt(1780112000000L);
        ConversationSessionListDTO page = new ConversationSessionListDTO();
        page.setItems(List.of(item));
        page.setNextCursor("cursor_001");
        when(conversationService.listSessions(eq("uk_default"), eq(20), eq(null))).thenReturn(page);

        mockMvc.perform(get("/api/conversations")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].sessionId").value("cvs_test_001"))
                .andExpect(jsonPath("$.data.items[0].lastMessagePreview").value("InnoDB 是默认事务引擎。"))
                .andExpect(jsonPath("$.data.nextCursor").value("cursor_001"));
    }

    @Test
    void listSessions_shouldUseStableUserKeyInsteadOfAccessToken() throws Exception {
        ConversationSessionListDTO page = new ConversationSessionListDTO();
        when(conversationService.listSessions(eq("uk_5f62b684efaf59ed"), eq(20), eq(null))).thenReturn(page);

        mockMvc.perform(get("/api/conversations")
                        .header("X-Access-Token", "short-lived-token")
                        .header("X-User-Key", "browser-001")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void createMessage_shouldRejectWhenQueryMissing() throws Exception {
        mockMvc.perform(post("/api/conversations/cvs_test_001/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\":60,\"limit\":20,\"strategy\":\"KB_RRF_RERANK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request parameters."));
    }

    @Test
    void listMessages_shouldPassQueryParameters() throws Exception {
        ConversationTurnListDTO list = new ConversationTurnListDTO();
        list.setSessionId("cvs_test_001");
        when(conversationService.listMessages(eq("uk_default"), eq("cvs_test_001"), eq(15), eq("turn_001"))).thenReturn(list);

        mockMvc.perform(get("/api/conversations/cvs_test_001/messages")
                        .param("limit", "15")
                        .param("beforeTurnId", "turn_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("cvs_test_001"));
    }

    @Test
    void createMessage_shouldReturnConversationPayload() throws Exception {
        ConversationMessageResponseDTO response = new ConversationMessageResponseDTO();
        response.setSessionId("cvs_test_001");
        response.setTurnId("turn_test_001");
        response.setRewrittenQuery("mysql 架构中的 InnoDB 作用");
        response.setAnswer("InnoDB 是默认事务引擎。[1]");
        response.setCitations(List.of());
        ResultHitDTO primaryHit = new ResultHitDTO();
        primaryHit.setSegmentId("seg_text_001");
        primaryHit.setSnippet("InnoDB 是默认事务引擎。");
        primaryHit.setScore(0.91d);
        primaryHit.setPageNo(3);
        primaryHit.setHitType("TEXT_CHUNK");
        PreviewAnchorDTO anchor = new PreviewAnchorDTO();
        anchor.setPageNo(3);
        anchor.setChunkOrder(12);
        primaryHit.setAnchor(anchor);
        ResultCardDTO card = new ResultCardDTO();
        card.setAssetId("asset_001");
        card.setAssetType("PDF");
        card.setFileName("mysql-notes.pdf");
        card.setTitle("mysql-notes.pdf");
        card.setScore(0.91d);
        card.setHitCount(1);
        card.setPrimaryHit(primaryHit);
        card.setAdditionalHits(List.of());
        response.setResultCards(List.of(card));
        ConversationMessageResponseDTO.RetrievalTraceDTO trace = new ConversationMessageResponseDTO.RetrievalTraceDTO();
        trace.setTopK(60);
        trace.setLimit(20);
        trace.setStrategy("KB_RRF_RERANK");
        trace.setRewriteReason("rewrite_by_model");
        trace.setRetrievedCount(3);
        response.setRetrievalTrace(trace);
        response.setCreatedAt(1777520001000L);

        ConversationMessageRequestDTO request = new ConversationMessageRequestDTO();
        request.setQuery("那 InnoDB 呢");
        request.setTopK(60);
        request.setLimit(20);
        request.setStrategy("KB_RRF_RERANK");
        when(conversationService.createMessage(eq("uk_default"), eq("cvs_test_001"), eq(request))).thenReturn(response);

        mockMvc.perform(post("/api/conversations/cvs_test_001/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "那 InnoDB 呢",
                                  "topK": 60,
                                  "limit": 20,
                                  "strategy": "KB_RRF_RERANK"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.turnId").value("turn_test_001"))
                .andExpect(jsonPath("$.data.resultCards[0].assetId").value("asset_001"))
                .andExpect(jsonPath("$.data.resultCards[0].primaryHit.segmentId").value("seg_text_001"))
                .andExpect(jsonPath("$.data.resultCards[0].primaryHit.anchor.pageNo").value(3))
                .andExpect(jsonPath("$.data.retrievalTrace.topK").value(60))
                .andExpect(jsonPath("$.data.retrievalTrace.rewriteReason").value("rewrite_by_model"))
                .andExpect(jsonPath("$.data.retrievalTrace.retrievedCount").value(3));
    }

    @Test
    void listMessages_shouldReturnTurns() throws Exception {
        ConversationTurnListDTO list = new ConversationTurnListDTO();
        list.setSessionId("cvs_test_001");
        when(conversationService.listMessages(eq("uk_default"), eq("cvs_test_001"), eq(20), eq(null))).thenReturn(list);

        mockMvc.perform(get("/api/conversations/cvs_test_001/messages")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("cvs_test_001"));
    }

    @Test
    void renameSession_shouldReturnUpdatedSession() throws Exception {
        ConversationRenameRequestDTO request = new ConversationRenameRequestDTO();
        request.setTitle("新标题");
        ConversationSessionDTO session = new ConversationSessionDTO();
        session.setSessionId("cvs_test_001");
        session.setTitle("新标题");
        session.setUserId("uk_default");
        session.setStatus("ACTIVE");
        when(conversationService.renameSession(eq("uk_default"), eq("cvs_test_001"), eq(request))).thenReturn(session);

        mockMvc.perform(patch("/api/conversations/cvs_test_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("新标题"));
    }

    @Test
    void deleteSession_shouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/conversations/cvs_test_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(conversationService).deleteSession(eq("uk_default"), eq("cvs_test_001"));
    }
}
