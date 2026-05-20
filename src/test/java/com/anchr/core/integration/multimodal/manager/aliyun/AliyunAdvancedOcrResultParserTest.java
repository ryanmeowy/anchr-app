package com.anchr.core.integration.multimodal.manager.aliyun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.ingestion.domain.model.OcrParagraph;
import com.anchr.core.ingestion.domain.model.OcrStructuredResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunAdvancedOcrResultParserTest {

    private final AliyunAdvancedOcrResultParser parser = new AliyunAdvancedOcrResultParser(new ObjectMapper());

    @Test
    void parse_shouldBuildParagraphBboxesFromWordPoints() {
        String data = """
                {
                  "content": "first line\\nsecond line",
                  "orgWidth": 640,
                  "orgHeight": 480,
                  "prism_paragraphsInfo": [
                    {"paragraphId": 0, "word": "first line"},
                    {"paragraphId": 1, "word": "second line"}
                  ],
                  "prism_wordsInfo": [
                    {
                      "paragraphId": 0,
                      "word": "first",
                      "pos": [
                        {"x": 10, "y": 20},
                        {"x": 60, "y": 20},
                        {"x": 60, "y": 40},
                        {"x": 10, "y": 40}
                      ]
                    },
                    {
                      "paragraphId": 0,
                      "word": "line",
                      "pos": [
                        {"x": 70, "y": 18},
                        {"x": 120, "y": 18},
                        {"x": 120, "y": 42},
                        {"x": 70, "y": 42}
                      ]
                    },
                    {
                      "paragraphId": 1,
                      "word": "second",
                      "pos": [
                        {"x": 15, "y": 80},
                        {"x": 115, "y": 80},
                        {"x": 115, "y": 100},
                        {"x": 15, "y": 100}
                      ]
                    }
                  ]
                }
                """;

        OcrStructuredResult result = parser.parse(data);

        assertThat(result.getImageWidth()).isEqualTo(640);
        assertThat(result.getImageHeight()).isEqualTo(480);
        assertThat(result.getFullText()).isEqualTo("first line\nsecond line");
        assertThat(result.getParagraphs()).hasSize(2);
        OcrParagraph first = result.getParagraphs().getFirst();
        assertThat(first.getText()).isEqualTo("first line");
        assertThat(first.getBbox().getX()).isEqualTo(10);
        assertThat(first.getBbox().getY()).isEqualTo(18);
        assertThat(first.getBbox().getWidth()).isEqualTo(110);
        assertThat(first.getBbox().getHeight()).isEqualTo(24);
        assertThat(first.getBbox().getUnit()).isEqualTo("PIXEL");
        assertThat(first.getWords()).hasSize(2);
    }

    @Test
    void parse_shouldFallbackToWordParagraphsWhenParagraphsAreMissing() {
        String data = """
                {
                  "content": "only word",
                  "width": 320,
                  "height": 240,
                  "prism_wordsInfo": [
                    {
                      "word": "only word",
                      "pos": [
                        {"x": 1, "y": 2},
                        {"x": 21, "y": 2},
                        {"x": 21, "y": 12},
                        {"x": 1, "y": 12}
                      ]
                    }
                  ]
                }
                """;

        OcrStructuredResult result = parser.parse(data);

        assertThat(result.getImageWidth()).isEqualTo(320);
        assertThat(result.getImageHeight()).isEqualTo(240);
        assertThat(result.getParagraphs()).hasSize(1);
        assertThat(result.getParagraphs().getFirst().getText()).isEqualTo("only word");
        assertThat(result.getParagraphs().getFirst().getBbox().isValid()).isTrue();
    }
}
