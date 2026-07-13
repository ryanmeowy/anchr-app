package com.anchr.core.search.interfaces.rest;

import com.anchr.core.search.application.SegmentPreviewService;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;

@ExtendWith(MockitoExtension.class)
class PreviewControllerTest {

    @Mock
    private SegmentPreviewService segmentPreviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PreviewController(segmentPreviewService))
                .build();
    }

    @Test
    void getSegmentPreview_shouldReturnPreviewPayload() throws Exception {
        PreviewSegmentDTO preview = PreviewSegmentDTO.builder()
                .segmentId("seg-001")
                .assetId("asset-001")
                .assetType("PDF")
                .segmentType("TEXT_CHUNK")
                .fileName("mysql.pdf")
                .previewType("PDF")
                .previewUrl("https://preview.example.com/mysql.pdf")
                .expiresAt(1777520300000L)
                .content("InnoDB is a storage engine.")
                .anchor(PreviewAnchorDTO.builder()
                        .pageNo(3)
                        .chunkOrder(12)
                        .build())
                .build();
        when(segmentPreviewService.getSegmentPreview(eq("seg-001"), any())).thenReturn(preview);

        mockMvc.perform(post("/api/v1/preview/segments/seg-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-Access-Token", "token-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.segmentId").value("seg-001"))
                .andExpect(jsonPath("$.data.previewUrl").value("https://preview.example.com/mysql.pdf"))
                .andExpect(jsonPath("$.data.anchor.pageNo").value(3))
                .andExpect(jsonPath("$.data.content").value("InnoDB is a storage engine."))
                .andExpect(jsonPath("$.data.snippet").doesNotExist())
                .andExpect(jsonPath("$.data.surroundingChunks").doesNotExist());
    }

    @Test
    void segmentNeighbors_shouldNotBeExposed() throws Exception {
        mockMvc.perform(get("/api/v1/preview/segments/seg-001/neighbors")
                        .header("X-Access-Token", "token-a"))
                .andExpect(status().isNotFound());
    }
}
