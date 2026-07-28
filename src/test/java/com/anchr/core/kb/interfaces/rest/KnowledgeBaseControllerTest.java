package com.anchr.core.kb.interfaces.rest;

import com.anchr.core.kb.application.AssetPreviewService;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentAvailabilityStatus;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.interfaces.rest.dto.AssetPreviewDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private AssetPreviewService assetPreviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeBaseController(knowledgeBaseService, assetPreviewService))
                .build();
    }

    @Test
    void listDocuments_shouldExposeVersionWithoutStorageKeys() throws Exception {
        Asset asset = asset();
        when(knowledgeBaseService.listDocuments(
                "kb-1", "RAG", "pdf",
                DocumentAvailabilityStatus.ANSWERABLE, 1, 24))
                .thenReturn(new KnowledgeBaseService.DocumentPagedResult(List.of(asset), 3, 87, 1, 24));

        mockMvc.perform(get("/api/v1/kbs/kb-1/documents")
                        .param("page", "1")
                        .param("size", "24")
                        .param("keyword", "RAG")
                        .param("fileType", "pdf")
                        .param("availabilityStatus", "ANSWERABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("asset-1"))
                .andExpect(jsonPath("$.data.items[0].versionNo").value(3))
                .andExpect(jsonPath("$.data.items[0].availabilityStatus").value("ANSWERABLE"))
                .andExpect(jsonPath("$.data.segmentTotal").value(87))
                .andExpect(jsonPath("$.data.items[0].previewAvailable").value(true))
                .andExpect(jsonPath("$.data.items[0].previewObjectKey").doesNotExist());

        verify(knowledgeBaseService).listDocuments(
                "kb-1", "RAG", "pdf",
                DocumentAvailabilityStatus.ANSWERABLE, 1, 24);
    }

    @Test
    void previewDocument_shouldReturnAssetMetadataWithoutCitationContext() throws Exception {
        when(assetPreviewService.getPreview("kb-1", "asset-1"))
                .thenReturn(AssetPreviewDTO.builder()
                        .assetId("asset-1")
                        .kbId("kb-1")
                        .kbName("Library A")
                        .fileName("document.pdf")
                        .fileType("PDF")
                        .versionNo(3)
                        .previewType("PDF")
                        .previewUrl("https://preview")
                        .expiresAt(1777520300000L)
                        .build());

        mockMvc.perform(get("/api/v1/kbs/kb-1/documents/asset-1/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetId").value("asset-1"))
                .andExpect(jsonPath("$.data.versionNo").value(3))
                .andExpect(jsonPath("$.data.previewUrl").value("https://preview"))
                .andExpect(jsonPath("$.data.citationContext").doesNotExist())
                .andExpect(jsonPath("$.data.surroundingChunks").doesNotExist());
    }

    private Asset asset() {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("document.pdf")
                .fileType("PDF")
                .mimeType("application/pdf")
                .sizeBytes(1024L)
                .fileHash("hash")
                .versionNo(3)
                .previewObjectKey("preview/document.pdf")
                .parseStatus(DocumentParseStatus.SUCCESS)
                .indexStatus(DocumentIndexStatus.SUCCESS)
                .segmentCount(12)
                .indexedSegmentCount(12)
                .createdAt(LocalDateTime.of(2026, 7, 10, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 7, 10, 12, 0))
                .build();
    }
}
