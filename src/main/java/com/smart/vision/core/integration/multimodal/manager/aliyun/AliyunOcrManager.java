package com.smart.vision.core.integration.multimodal.manager.aliyun;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.smart.vision.core.integration.constant.AliyunConstant.BAILIAN_API_KEY_ENV_NAME;
import static com.smart.vision.core.integration.constant.AliyunConstant.VISION_MODEL_NAME;
import static com.smart.vision.core.common.constant.ValidationConstant.AI_RESPONSE_REGEX;
import static com.smart.vision.core.integration.multimodal.domain.model.PromptEnum.OCR;

/**
 * Aliyun OCR Service
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.capability-provider", name = "ocr", havingValue = "aliyun")
public class AliyunOcrManager {

    private static final Pattern TEXT_PATTERN = Pattern.compile(AI_RESPONSE_REGEX);

    @Retryable(retryFor = {Exception.class}, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String llmOcrContent(String imageUrl) throws NoApiKeyException, UploadFileException {
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role("user")
                .content(Arrays.asList(
                        Map.of("image", imageUrl),
                        Map.of("text", OCR.getPrompt())
                ))
                .build();

        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(resolveVisionModel())
                .message(userMessage)
                .apiKey(resolveApiKey())
                .build();
        MultiModalConversationResult result;
        MultiModalConversation conv = new MultiModalConversation();
        result = conv.call(param);

        String content = Optional.ofNullable(result)
                .map(MultiModalConversationResult::getOutput)
                .map(MultiModalConversationOutput::getChoices)
                .filter(CollectionUtil::isNotEmpty)
                .map(List::getFirst)
                .map(MultiModalConversationOutput.Choice::getMessage)
                .map(MultiModalMessage::getContent)
                .map(Object::toString)
                .orElse(Strings.EMPTY);

        Matcher matcher = TEXT_PATTERN.matcher(content);
        return matcher.find()
                ? StringUtils.hasText(matcher.group(1)) && !matcher.group(1).equals("-1") ? matcher.group(1) : Strings.EMPTY
                : Strings.EMPTY;
    }

    private String resolveApiKey() {
        return Optional.ofNullable(System.getenv(BAILIAN_API_KEY_ENV_NAME))
                .orElseThrow(() -> new BusinessException(ApiError.INVALID_API_KEY));
    }

    private String resolveVisionModel() {
        return VISION_MODEL_NAME;
    }
}
