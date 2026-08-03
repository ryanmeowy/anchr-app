package com.anchr.core.integration.ai.adapter;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.conversation.application.model.*;
import com.anchr.core.conversation.domain.port.AgentModelPort;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
public class SpringAiAgentModelAdapter implements AgentModelPort {
    private final CapabilityResolver capabilityResolver;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public AgentModelResponse respond(AgentModelRequest request) {
        CapabilityConfig config = capabilityResolver.activeForSlot(CapabilityResolver.SLOT_GENERATION)
                .orElseThrow(() -> new IllegalStateException("Generation is not configured"));
        Duration timeout = request.options() == null || request.options().timeout() == null
                ? Duration.ofSeconds(30) : request.options().timeout();
        long deadline = System.currentTimeMillis() + Math.max(1L, timeout.toMillis());
        boolean nativeTools = request.options() != null && request.options().toolsEnabled()
                && !"JSON".equalsIgnoreCase(request.options().toolCallMode());
        AgentModelResponse response;
        try {
            response = call(config, request, nativeTools, deadline);
        } catch (RuntimeException nativeFailure) {
            boolean autoFallback = nativeTools && request.options() != null
                    && "AUTO".equalsIgnoreCase(request.options().toolCallMode())
                    && System.currentTimeMillis() < deadline;
            if (!autoFallback) throw nativeFailure;
            response = call(config, request, false, deadline);
        }
        return response;
    }

    private AgentModelResponse call(CapabilityConfig config, AgentModelRequest request,
                                    boolean nativeTools, long deadline) {
        OpenAiChatOptions options = buildOptions(config, request, nativeTools);
        OpenAiApi api = buildApi(config);
        Future<AgentModelResponse> future = executor.submit(() -> execute(api, request.messages(), options));
        try {
            return future.get(Math.max(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Agent model call timed out", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent model call interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Agent model call failed", e.getCause());
        }
    }

    private OpenAiApi buildApi(CapabilityConfig config) {
        String baseUrl = trimSlash(config.getBaseUrl());
        return OpenAiApi.builder().baseUrl(baseUrl).apiKey(aesUtil.decrypt(config.getApiKeyEnc()))
                .completionsPath("/chat/completions").build();
    }

    private AgentModelResponse execute(OpenAiApi api, List<AgentMessage> messages,
                                       OpenAiChatOptions options) {
        OpenAiApi.ChatCompletionRequest baseRequest = new OpenAiApi.ChatCompletionRequest(
                toApiMessages(messages), false);
        OpenAiApi.ChatCompletionRequest apiRequest = ModelOptionsUtils.merge(
                options, baseRequest, OpenAiApi.ChatCompletionRequest.class);
        OpenAiApi.ChatCompletion completion = api.chatCompletionEntity(apiRequest).getBody();
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()
                || completion.choices().getFirst().message() == null) {
            throw new IllegalStateException("Empty agent model response");
        }
        OpenAiApi.ChatCompletion.Choice choice = completion.choices().getFirst();
        OpenAiApi.ChatCompletionMessage output = choice.message();
        List<AgentToolCall> toolCalls = output.toolCalls() == null ? List.of()
                : output.toolCalls().stream()
                .filter(call -> call.function() != null)
                .map(call -> new AgentToolCall(call.id(), call.function().name(),
                        call.function().arguments()))
                .toList();
        OpenAiApi.Usage usage = completion.usage();
        AgentTokenUsage tokenUsage = usage == null ? AgentTokenUsage.EMPTY : new AgentTokenUsage(
                value(usage.promptTokens()), value(usage.completionTokens()));
        return new AgentModelResponse(output.content(), toolCalls, tokenUsage,
                completion.model() == null ? options.getModel() : completion.model(),
                choice.finishReason() == null ? null : choice.finishReason().name(),
                completion.id(), output.reasoningContent());
    }

    OpenAiChatOptions buildOptions(CapabilityConfig config, AgentModelRequest request, boolean nativeTools) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(config.getModelName());
        Map<String, Object> extra = parseExtra(config.getExtraConfig());
        options.setTemperature(number(extra.get("temperature"), request.options() == null ? null : request.options().temperature()));
        options.setMaxTokens(integer(extra.get("max_tokens"), request.options() == null ? null : request.options().maxTokens()));
        options.setParallelToolCalls(false);
        options.setInternalToolExecutionEnabled(false);
        Map<String, Object> extraBody = new LinkedHashMap<>(extra);
        extraBody.remove("temperature");
        extraBody.remove("max_tokens");
        if (!extraBody.isEmpty()) options.setExtraBody(extraBody);
        if (nativeTools) {
            options.setTools(request.tools().stream().map(tool -> new OpenAiApi.FunctionTool(
                    new OpenAiApi.FunctionTool.Function(tool.description(), tool.name(), tool.inputSchema()))).toList());
            options.setToolChoice(nativeToolChoice(request));
        }
        return options;
    }

    private String nativeToolChoice(AgentModelRequest request) {
        String configured = request.options() == null ? null : request.options().nativeToolChoice();
        return "REQUIRED".equalsIgnoreCase(configured == null ? "" : configured.trim())
                ? "required" : "auto";
    }

    List<OpenAiApi.ChatCompletionMessage> toApiMessages(List<AgentMessage> source) {
        List<OpenAiApi.ChatCompletionMessage> result = new ArrayList<>();
        for (AgentMessage message : source) {
            switch (message.role()) {
                case "system" -> result.add(new OpenAiApi.ChatCompletionMessage(
                        message.content(), OpenAiApi.ChatCompletionMessage.Role.SYSTEM));
                case "user" -> result.add(new OpenAiApi.ChatCompletionMessage(
                        message.content(), OpenAiApi.ChatCompletionMessage.Role.USER));
                case "assistant" -> result.add(new OpenAiApi.ChatCompletionMessage(
                        message.content() == null ? "" : message.content(),
                        OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                        null, null,
                        message.toolCalls().isEmpty() ? null : message.toolCalls().stream()
                                .map(call -> new OpenAiApi.ChatCompletionMessage.ToolCall(
                                        call.id(), "function",
                                        new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                                call.name(), call.arguments())))
                                .toList(),
                        null, null, null, message.reasoningContent()));
                case "tool" -> result.add(new OpenAiApi.ChatCompletionMessage(
                        message.content(), OpenAiApi.ChatCompletionMessage.Role.TOOL,
                        message.toolName(), message.toolCallId(), null,
                        null, null, null, null));
                default -> throw new IllegalArgumentException("Unsupported agent message role: " + message.role());
            }
        }
        return result;
    }

    private Map<String, Object> parseExtra(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    private Double number(Object value, Double override) {
        if (override != null) return override;
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Integer integer(Object value, Integer override) {
        if (override != null) return override;
        return value instanceof Number number ? number.intValue() : null;
    }

    private int value(Integer value) { return value == null ? 0 : value; }
    private String trimSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
