package com.anchr.core.common.model;

import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BboxInfoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void segmentDocument_shouldDeserializeBboxInfoFromEsSource() throws Exception {
        String source = """
                {
                  "segmentId": "segment-1",
                  "bbox": [
                    {
                      "pageNo": 1,
                      "legacyField": "ignored",
                      "bbox": {
                        "l": 10.5,
                        "t": 20.5,
                        "r": 110.5,
                        "b": 120.5,
                        "coord_origin": "BOTTOMLEFT"
                      }
                    }
                  ]
                }
                """;

        SegmentDocument document = objectMapper.readValue(source, SegmentDocument.class);

        assertThat(document.getBbox()).hasSize(1);
        BboxInfo bboxInfo = document.getBbox().getFirst();
        assertThat(bboxInfo.getPageNo()).isEqualTo(1);
        assertThat(bboxInfo.getBbox().getL()).isEqualTo(10.5);
        assertThat(bboxInfo.getBbox().getCoordOrigin()).isEqualTo("BOTTOMLEFT");
    }
}
