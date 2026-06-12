package com.anchr.core.integration.ai;

import com.anchr.core.auth.infrastructure.AesUtil;
import com.anchr.core.common.model.GraphTriple;
import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import com.anchr.core.ingestion.domain.port.IngestionContentPort;
import com.anchr.core.search.domain.port.QueryGraphParserPort;
import com.anchr.core.search.domain.port.SearchContentPort;
import com.anchr.core.search.interfaces.rest.dto.GraphTripleDTO;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible generation adapter backed by capability_config.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenGenerationAdapter
        implements ConversationRewritePort, SearchContentPort, IngestionContentPort, QueryGraphParserPort {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CapabilityConfigRepository configRepository;
    private final AesUtil aesUtil;

    // ── ConversationRewritePort ──────────────────────────────────────────

    @Override
    public String generateText(String prompt) {
        return generate(List.of(Map.of("role", "user", "content", prompt))).content();
    }

    // ── SearchContentPort ────────────────────────────────────────────────

    @Override
    public String generateSummary(String imageInput) {
        return generateWithImage("请用一段话描述这张图片的内容。", imageInput).content();
    }

    @Override
    public List<String> generateTags(String imageInput) {
        String json = generateWithImage("生成3-5个标签，输出JSON数组: [\"tag1\", \"tag2\"]。只输出JSON。", imageInput).content();
        try {
            return objectMapper.readValue(extractJsonArray(json), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tags JSON: {}", json);
            return List.of();
        }
    }

    @Override
    public List<GraphTriple> generateGraph(String imageInput) {
        String json = generateWithImage(
                "提取图中物体的SPO三元组，输出JSON数组: [{s:主体, p:关系, o:客体}]。只输出JSON。",
                imageInput).content();
        try {
            List<Map<String, String>> raw = objectMapper.readValue(
                    extractJsonArray(json), new TypeReference<>() {});
            return raw.stream()
                    .map(m -> new GraphTriple(
                            m.getOrDefault("s", ""),
                            m.getOrDefault("p", ""),
                            m.getOrDefault("o", "")))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse graph JSON: {}", json);
            return List.of();
        }
    }

    // ── IngestionContentPort ─────────────────────────────────────────────

    @Override
    public String generateFileName(String imageInput) {
        return generateWithImage("为图片生成3-6字中文名，只输出名称。", imageInput).content().trim();
    }

    // ── QueryGraphParserPort ─────────────────────────────────────────────

    @Override
    public List<GraphTripleDTO> parseFromKeyword(String keyword) {
        String json = generateText("提取关键词'" + keyword + "'的实体关系三元组，"
                + "输出JSON数组: [{s:主体, p:关系, o:客体}]。只输出JSON。").trim();
        try {
            List<Map<String, String>> raw = objectMapper.readValue(
                    extractJsonArray(json), new TypeReference<>() {});
            return raw.stream()
                    .map(m -> new GraphTripleDTO(
                            m.getOrDefault("s", ""),
                            m.getOrDefault("p", ""),
                            m.getOrDefault("o", "")))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse graph from keyword: {}", json);
            return List.of();
        }
    }

    // ── internal ─────────────────────────────────────────────────────────

    private GenerationClient.GenerationResult generate(List<Map<String, String>> messages) {
        CapabilityConfig config = loadConfig();
        GenerationClient client = new GenerationClient(config.getBaseUrl(), decrypt(config.getApiKeyEnc()));
        return client.generate(config.getModelName(), messages, null);
    }

    private GenerationClient.GenerationResult generateWithImage(String prompt, String imageInput) {
        CapabilityConfig config = loadConfig();
        GenerationClient client = new GenerationClient(config.getBaseUrl(), decrypt(config.getApiKeyEnc()));
        List<Map<String, String>> messages = List.of(Map.of(
                "role", "user",
                "content", "[{\"type\":\"image_url\",\"image_url\":{\"url\":\""
                        + imageInput + "\"}},{\"type\":\"text\",\"text\":\"" + prompt + "\"}]"));
        return client.generate(config.getModelName(), messages, null);
    }

    private CapabilityConfig loadConfig() {
        return configRepository.findByCapability("GENERATION")
                .orElseThrow(() -> new IllegalStateException(
                        "Generation is not configured. Save config via PATCH /api/v1/settings/generation."));
    }

    private String decrypt(String encrypted) {
        try { return aesUtil.decrypt(encrypted); }
        catch (Exception e) { throw new IllegalStateException("Failed to decrypt generation apiKey.", e); }
    }

    static String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }
}
