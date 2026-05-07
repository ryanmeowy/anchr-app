package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.domain.model.Bbox;
import com.smart.vision.core.search.domain.model.KbAssetTypeEnum;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.model.SegmentType;
import com.smart.vision.core.search.domain.repository.KbSegmentRepository;
import com.smart.vision.core.search.interfaces.rest.dto.PreviewSegmentDTO;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentPreviewServiceImplTest {

    @Test
    void getSegmentPreview_shouldExposeImageOcrAnchor() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(kbSegmentRepository);
        Segment segment = buildImageOcrSegment();
        when(kbSegmentRepository.findBySegmentId("asset-1:ocr:0")).thenReturn(Optional.of(segment));

        PreviewSegmentDTO preview = service.getSegmentPreview("asset-1:ocr:0");

        assertThat(preview.getSegmentId()).isEqualTo("asset-1:ocr:0");
        assertThat(preview.getPreviewType()).isEqualTo("IMAGE");
        assertThat(preview.getPreviewUrl()).isEqualTo("oss://image-a.png");
        assertThat(preview.getSnippet()).isEqualTo("设备故障代码 E102");
        assertThat(preview.getAnchor().getBbox().getX()).isEqualTo(120);
        assertThat(preview.getAnchor().getBbox().getUnit()).isEqualTo("PIXEL");
        assertThat(preview.getAnchor().getImageWidth()).isEqualTo(1920);
        assertThat(preview.getAnchor().getImageHeight()).isEqualTo(1080);
    }

    @Test
    void getSegmentPreview_shouldThrowWhenSegmentNotFound() {
        KbSegmentRepository kbSegmentRepository = mock(KbSegmentRepository.class);
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(kbSegmentRepository);
        when(kbSegmentRepository.findBySegmentId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSegmentPreview("missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Segment not found.");
    }

    private Segment buildImageOcrSegment() {
        return Segment.builder()
                .segmentId("asset-1:ocr:0")
                .assetId("asset-1")
                .assetType(KbAssetTypeEnum.IMAGE)
                .segmentType(SegmentType.IMAGE_OCR_BLOCK)
                .title("image-a.png")
                .ocrText("设备故障代码 E102")
                .sourceRef("oss://image-a.png")
                .thumbnail("oss://image-a.png")
                .ocrSummary("设备故障代码 E102")
                .bbox(Bbox.builder()
                        .x(120)
                        .y(80)
                        .width(360)
                        .height(48)
                        .unit("PIXEL")
                        .build())
                .imageWidth(1920)
                .imageHeight(1080)
                .build();
    }
}
