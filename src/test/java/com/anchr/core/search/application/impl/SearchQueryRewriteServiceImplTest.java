package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.model.SearchRewriteResult;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchQueryRewriteServiceImplTest {

    @Test
    void rewrite_shouldReturnProfessionalQueryAndDistinctConceptKeywords() {
        SearchGenerationPort generationPort = mock(SearchGenerationPort.class);
        when(generationPort.generateText(anyString())).thenReturn("""
                {
                  "rewrittenQuery": "PDF 上传后无法检索其中图片的故障原因",
                  "keywords": ["PDF", "图片检索", "索引故障", "PDF"],
                  "intent": "故障原因排查",
                  "category": "troubleshooting"
                }
                """);
        TestContext context = context(generationPort);

        SearchRewriteResult result = context.service().rewrite("请问 PDF 上传后为什么搜不到里面的图片呢？");

        assertThat(result.isFallbackUsed()).isFalse();
        assertThat(result.getRewrittenQuery()).isEqualTo("PDF 上传后无法检索其中图片的故障原因");
        assertThat(result.getKeywords()).containsExactly("PDF", "图片检索", "索引故障");
        assertThat(result.getIntent()).isEqualTo("故障原因排查");
        assertThat(result.getIntentCategory()).isEqualTo("TROUBLESHOOTING");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(generationPort).generateText(prompt.capture());
        assertThat(prompt.getValue())
                .contains("rewrittenQuery", "不得生成同义词", "每个 keyword 必须代表不同的信息维度")
                .contains("请问 PDF 上传后为什么搜不到里面的图片呢？");
        verify(context.valueOperations()).set(
                ArgumentMatchers.contains(":v2:"),
                anyString(),
                ArgumentMatchers.any());
    }

    @Test
    void rewrite_shouldFallbackWhenRewrittenQueryIsMissing() {
        SearchGenerationPort generationPort = mock(SearchGenerationPort.class);
        when(generationPort.generateText(anyString())).thenReturn("""
                {"keywords":["PDF","图片检索"],"intent":"故障排查","category":"TROUBLESHOOTING"}
                """);
        TestContext context = context(generationPort);

        SearchRewriteResult result = context.service().rewrite("PDF 图片为什么搜不到");

        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getRewrittenQuery()).isEqualTo("PDF 图片为什么搜不到");
        assertThat(result.getKeywords()).isEqualTo(List.of());
    }

    @SuppressWarnings("unchecked")
    private TestContext context(SearchGenerationPort generationPort) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        SearchQueryRewriteServiceImpl service = new SearchQueryRewriteServiceImpl(
                generationPort,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                redisTemplate
        );
        return new TestContext(service, valueOperations);
    }

    private record TestContext(SearchQueryRewriteServiceImpl service,
                               ValueOperations<String, String> valueOperations) {
    }
}
