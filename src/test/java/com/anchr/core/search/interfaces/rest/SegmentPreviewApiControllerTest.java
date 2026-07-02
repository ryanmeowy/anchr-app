package com.anchr.core.search.interfaces.rest;

import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.search.application.SegmentPreviewService;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import com.anchr.core.search.interfaces.rest.dto.SurroundingChunkDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                        .bbox(List.of(BboxInfo.builder()
                                .pageNo(3)
                                .bbox(BboxInfo.Bbox.builder()
                                        .l(100.0)
                                        .t(200.0)
                                        .r(300.0)
                                        .b(400.0)
                                        .coordOrigin("bottom-left")
                                        .build())
                                .build()))
                        .build()))
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
                .andExpect(jsonPath("$.data.surroundingChunks[0].relation").value("current"))
                .andExpect(jsonPath("$.data.surroundingChunks[0].bbox[0].pageNo").value(3))
                .andExpect(jsonPath("$.data.surroundingChunks[0].bbox[0].bbox.l").value(100.0))
                .andExpect(jsonPath("$.data.surroundingChunks[0].bbox[0].bbox.t").value(200.0))
                .andExpect(jsonPath("$.data.surroundingChunks[0].bbox[0].bbox.r").value(300.0))
                .andExpect(jsonPath("$.data.surroundingChunks[0].bbox[0].bbox.b").value(400.0))
                .andExpect(jsonPath("$.data.surroundingChunks[0].bbox[0].bbox.coordOrigin").value("bottom-left"));
    }
}
