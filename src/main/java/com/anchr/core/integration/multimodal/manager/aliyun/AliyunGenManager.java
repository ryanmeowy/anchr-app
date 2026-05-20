package com.anchr.core.integration.multimodal.manager.aliyun;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.aliyun.core.utils.StringUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.model.GraphTriple;
import com.anchr.core.integration.multimodal.domain.model.PromptEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.anchr.core.integration.constant.AliyunConstant.BAILIAN_API_KEY_ENV_NAME;
import static com.anchr.core.integration.constant.AliyunConstant.IMAGE_GEN_MODEL_NAME;
import static com.anchr.core.integration.constant.AliyunConstant.TEXT_MODEL_NAME;
import static com.anchr.core.integration.constant.AliyunConstant.VISION_MODEL_NAME;
import static com.anchr.core.common.constant.CommonConstant.DEFAULT_IMAGE_NAME;
import static com.anchr.core.common.constant.ValidationConstant.AI_RESPONSE_REGEX;
import static com.anchr.core.common.constant.ValidationConstant.MD_JSON_REGEX;
import static com.anchr.core.integration.multimodal.domain.model.PromptEnum.GRAPH_IMAGE;
import static com.anchr.core.integration.multimodal.domain.model.PromptEnum.GRAPH_TEXT;
import static com.anchr.core.integration.multimodal.domain.model.PromptEnum.TAG_GEN;

/**
 * AliyunGenManager is a management class for handling operations related to Aliyun generation.
 * This class primarily facilitates image generation through Aliyun multimodal conversation API.
 *
 * @author Ryan
 * @since 2025/12/23
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "gen", havingValue = "aliyun")
public class AliyunGenManager {

    private final Gson gson;

    private static final Pattern TEXT_PATTERN = Pattern.compile(AI_RESPONSE_REGEX);

    private final Pattern MD_JSON_PATTERN = Pattern.compile(MD_JSON_REGEX, Pattern.DOTALL);

    public String generateSummary(String imageUrl) throws NoApiKeyException, UploadFileException {
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(Arrays.asList(Map.of("image", imageUrl), Map.of("text", PromptEnum.DEFAULT.getPrompt())))
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(resolveImageGenModel())
                .message(userMessage)
                .apiKey(resolveApiKey())
                .build();
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationResult callResult = conv.call(param);
        String rawText = Optional.ofNullable(callResult)
                .map(MultiModalConversationResult::getOutput)
                .map(MultiModalConversationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(MultiModalConversationOutput.Choice::getMessage)
                .map(MultiModalMessage::getContent)
                .map(Object::toString)
                .orElse(Strings.EMPTY);
        Matcher matcher = TEXT_PATTERN.matcher(rawText);
        return matcher.find() ? matcher.group(1) : rawText;
    }

    @Retryable(retryFor = Exception.class, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String genFileName(String imageUrl) throws NoApiKeyException, UploadFileException {
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(Arrays.asList(Map.of("image", imageUrl), Map.of("text", PromptEnum.NAME_GEN.getPrompt())))
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(resolveImageGenModel())
                .message(userMessage)
                .apiKey(resolveApiKey())
                .build();
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationResult callResult = conv.call(param);
        String rawName = Optional.ofNullable(callResult)
                .map(MultiModalConversationResult::getOutput)
                .map(MultiModalConversationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(MultiModalConversationOutput.Choice::getMessage)
                .map(MultiModalMessage::getContent)
                .map(Object::toString)
                .orElse(Strings.EMPTY);
        Matcher matcher = TEXT_PATTERN.matcher(rawName);
        return matcher.find() ? matcher.group(1) : DEFAULT_IMAGE_NAME;
    }

    /**
     * Call VL model to generate tags
     */
    @Retryable(retryFor = Exception.class, backoff = @Backoff(delay = 1000, multiplier = 2))
    public List<String> generateTags(String imageUrl) throws NoApiKeyException, UploadFileException {
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role("user")
                .content(Arrays.asList(
                        Map.of("image", imageUrl),
                        Map.of("text", TAG_GEN.getPrompt())
                ))
                .build();

        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(resolveVisionModel())
                .message(userMessage)
                .apiKey(resolveApiKey())
                .build();

        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationResult result = conv.call(param);
        String content = Optional.ofNullable(result)
                .map(MultiModalConversationResult::getOutput)
                .map(MultiModalConversationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(MultiModalConversationOutput.Choice::getMessage)
                .map(MultiModalMessage::getContent)
                .map(Object::toString)
                .orElse(Strings.EMPTY);
        return parseMdJson(content, String.class);
    }

    @Retryable(retryFor = Exception.class, backoff = @Backoff(delay = 1000, multiplier = 2))
    public List<GraphTriple> generateGraph(String imageUrl) throws NoApiKeyException, UploadFileException {
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role("user")
                .content(Arrays.asList(Map.of("image", imageUrl), Map.of("text", GRAPH_IMAGE.getPrompt())))
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(resolveVisionModel())
                .message(userMessage)
                .apiKey(resolveApiKey())
                .build();
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationResult result = conv.call(param);
        String content = Optional.ofNullable(result)
                .map(MultiModalConversationResult::getOutput)
                .map(MultiModalConversationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(MultiModalConversationOutput.Choice::getMessage)
                .map(MultiModalMessage::getContent)
                .map(Object::toString)
                .orElse(Strings.EMPTY);
        return parseMdJson(content, GraphTriple.class);
    }

    @Retryable(retryFor = Exception.class, backoff = @Backoff(delay = 1000, multiplier = 2))
    public List<GraphTriple> praseTriplesFromKeyword(String keyword) throws NoApiKeyException, InputRequiredException {
        Generation gen = new Generation();
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(GRAPH_TEXT.getPrompt())
                .build();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(keyword)
                .build();
        GenerationParam param = GenerationParam.builder()
                .apiKey(resolveApiKey())
                .model(resolveTextModel())
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        GenerationResult result = gen.call(param);
        String content = Optional.of(result)
                .map(GenerationResult::getOutput)
                .map(GenerationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(GenerationOutput.Choice::getMessage)
                .map(Message::getContent)
                .orElse(Strings.EMPTY);
        return parseMdJson(content, GraphTriple.class);
    }

    @Retryable(retryFor = Exception.class, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String enhanceOcrParagraph(String imageUrl, String prompt) throws NoApiKeyException, UploadFileException {
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(Arrays.asList(Map.of("image", imageUrl), Map.of("text", prompt)))
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(resolveImageGenModel())
                .message(userMessage)
                .apiKey(resolveApiKey())
                .build();
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationResult callResult = conv.call(param);
        String rawText = Optional.ofNullable(callResult)
                .map(MultiModalConversationResult::getOutput)
                .map(MultiModalConversationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(MultiModalConversationOutput.Choice::getMessage)
                .map(MultiModalMessage::getContent)
                .map(Object::toString)
                .orElse(Strings.EMPTY);
        Matcher matcher = TEXT_PATTERN.matcher(rawText);
        return matcher.find() ? matcher.group(1) : rawText;
    }

    @Retryable(retryFor = Exception.class, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String generateText(String prompt) throws NoApiKeyException, InputRequiredException {
        Generation gen = new Generation();
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("You are a retrieval query rewriter.")
                .build();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();
        GenerationParam param = GenerationParam.builder()
                .apiKey(resolveApiKey())
                .model(resolveTextModel())
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        GenerationResult result = gen.call(param);
        return Optional.of(result)
                .map(GenerationResult::getOutput)
                .map(GenerationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(GenerationOutput.Choice::getMessage)
                .map(Message::getContent)
                .orElse(Strings.EMPTY);
    }

    private <T> List<T> parseMdJson(String content, Class<T> clazz) {
        if (StringUtils.isBlank(content)) {
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Model returned empty content.");
        }
        try {
            Matcher matcher = MD_JSON_PATTERN.matcher(content);
            if (matcher.find()) {
                String jsonArray = matcher.group(1);
                Type type = TypeToken.getParameterized(List.class, clazz).getType();
                List<T> parsed = gson.fromJson(jsonArray, type);
                if (parsed == null || parsed.isEmpty()) {
                    throw new BusinessException(ApiError.INTERNAL_ERROR, "Parsed JSON array is empty.");
                }
                return parsed;
            }
            List<T> parsed = gson.fromJson(content, TypeToken.getParameterized(List.class, clazz).getType());
            if (parsed == null || parsed.isEmpty()) {
                throw new BusinessException(ApiError.INTERNAL_ERROR, "Parsed JSON payload is empty.");
            }
            return parsed;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("md json parsing failed, original content: {}", content);
            throw new BusinessException(ApiError.INTERNAL_ERROR, "Failed to parse model json payload.", e);
        }
    }

    private String resolveApiKey() {
        return Optional.ofNullable(System.getenv(BAILIAN_API_KEY_ENV_NAME))
                .orElseThrow(() -> new BusinessException(ApiError.INVALID_API_KEY));
    }

    private String resolveImageGenModel() {
        return IMAGE_GEN_MODEL_NAME;
    }

    private String resolveVisionModel() {
        return VISION_MODEL_NAME;
    }

    private String resolveTextModel() {
        return TEXT_MODEL_NAME;
    }
}
