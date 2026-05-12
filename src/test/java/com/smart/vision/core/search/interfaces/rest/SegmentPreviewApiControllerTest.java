package com.smart.vision.core.search.interfaces.rest;

import com.smart.vision.core.common.exception.GlobalExceptionHandler;
import com.smart.vision.core.search.application.SegmentPreviewService;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.smart.vision.core.search.interfaces.rest.dto.SurroundingChunkDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SegmentPreviewApiControllerTest {

    @Mock
    private SegmentPreviewService segmentPreviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SegmentPreviewApiController(segmentPreviewService))
                .setControllerAdvice(new GlobalExceptionHandler())
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
                .snippet("InnoDB is a storage engine.")
                .anchor(PreviewAnchorDTO.builder()
                        .pageNo(3)
                        .chunkOrder(12)
                        .build())
                .surroundingChunks(List.of(SurroundingChunkDTO.builder()
                        .segmentId("seg-001")
                        .chunkOrder(12)
                        .pageNo(3)
                        .content("InnoDB is a storage engine.")
                        .relation("current")
                        .build()))
                .build();
        when(segmentPreviewService.getSegmentPreview(eq("seg-001"), eq("token-a"))).thenReturn(preview);

        mockMvc.perform(get("/api/v1/preview/segments/seg-001")
                        .header("X-Access-Token", "token-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.segmentId").value("seg-001"))
                .andExpect(jsonPath("$.data.previewUrl").value("https://preview.example.com/mysql.pdf"))
                .andExpect(jsonPath("$.data.anchor.pageNo").value(3))
                .andExpect(jsonPath("$.data.surroundingChunks[0].relation").value("current"));
    }
}
