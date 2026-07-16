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
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
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
        ChatResponse response;
        try {
            response = call(config, request, nativeTools, deadline);
        } catch (RuntimeException nativeFailure) {
            boolean autoFallback = nativeTools && request.options() != null
                    && "AUTO".equalsIgnoreCase(request.options().toolCallMode())
                    && System.currentTimeMillis() < deadline;
            if (!autoFallback) throw nativeFailure;
            response = call(config, request, false, deadline);
        }
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("Empty agent model response");
        }
        AssistantMessage output = response.getResult().getOutput();
        List<AgentToolCall> toolCalls = output.getToolCalls().stream()
                .map(call -> new AgentToolCall(call.id(), call.name(), call.arguments())).toList();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        AgentTokenUsage tokenUsage = usage == null ? AgentTokenUsage.EMPTY : new AgentTokenUsage(
                value(usage.getPromptTokens()), value(usage.getCompletionTokens()));
        String finishReason = response.getResult().getMetadata() == null ? null
                : response.getResult().getMetadata().getFinishReason();
        return new AgentModelResponse(output.getText(), toolCalls, tokenUsage,
                response.getMetadata() == null ? config.getModelName() : response.getMetadata().getModel(),
                finishReason, response.getMetadata() == null ? null : response.getMetadata().getId());
    }

    private ChatResponse call(CapabilityConfig config, AgentModelRequest request,
                              boolean nativeTools, long deadline) {
        OpenAiChatOptions options = buildOptions(config, request, nativeTools);
        OpenAiChatModel model = buildModel(config, options);
        Future<ChatResponse> future = executor.submit(() -> model.call(new Prompt(toMessages(request.messages()), options)));
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

    private OpenAiChatModel buildModel(CapabilityConfig config, OpenAiChatOptions options) {
        String baseUrl = trimSlash(config.getBaseUrl());
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(aesUtil.decrypt(config.getApiKeyEnc()))
                .completionsPath("/chat/completions").build();
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).build();
    }

    private OpenAiChatOptions buildOptions(CapabilityConfig config, AgentModelRequest request, boolean nativeTools) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(config.getModelName());
        Map<String, Object> extra = parseExtra(config.getExtraConfig());
        options.setTemperature(number(extra.get("temperature"), request.options() == null ? null : request.options().temperature()));
        options.setMaxTokens(integer(extra.get("max_tokens"), request.options() == null ? null : request.options().maxTokens()));
        options.setParallelToolCalls(false);
        options.setInternalToolExecutionEnabled(false);
        if (nativeTools) {
            options.setTools(request.tools().stream().map(tool -> new OpenAiApi.FunctionTool(
                    new OpenAiApi.FunctionTool.Function(tool.name(), tool.description(), tool.inputSchema()))).toList());
            options.setToolChoice("auto");
        }
        return options;
    }

    private List<Message> toMessages(List<AgentMessage> source) {
        List<Message> result = new ArrayList<>();
        for (AgentMessage message : source) {
            switch (message.role()) {
                case "system" -> result.add(new SystemMessage(message.content()));
                case "user" -> result.add(new UserMessage(message.content()));
                case "assistant" -> result.add(message.toolCalls().isEmpty()
                        ? new AssistantMessage(message.content() == null ? "" : message.content())
                        : AssistantMessage.builder().content(message.content() == null ? "" : message.content())
                        .toolCalls(message.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(
                                call.id(), "function", call.name(), call.arguments())).toList()).build());
                case "tool" -> result.add(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(message.toolCallId(), message.toolName(), message.content()))).build());
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
