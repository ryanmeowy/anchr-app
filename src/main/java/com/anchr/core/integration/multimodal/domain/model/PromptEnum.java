package com.anchr.core.integration.multimodal.domain.model;

import lombok.Getter;

/**
 * Preset prompt templates for image-to-text styles
 *
 * @author Ryan
 * @since 2025/12/23
 */
@Getter
public enum PromptEnum {
    DEFAULT("default", "请详细描述这张图片的内容;"),
    NAME_GEN("name_gen", "为所附图片生成一个3-6字的中文图片名，要求简洁、达意、富有美感，直接输出名称即可。"),
    TAG_GEN("tag_gen", "请分析这张图片，提取 3-5 个核心标签，包含物体、场景、风格。 请直接返回一个 JSON 字符串数组，不要包含 Markdown 格式或其他废话。例如：[\"风景\", \"雪山\", \"日落\"]"),
    OCR("ocr", "请精确提取图中的所有文本内容，包括印刷体和清晰的手写体。请忽略水印，并丢弃无意义的文本（如单个标点符号、无上下文的孤立字符）。若图中没有文本、文本无法识别或难以识别，请输出“-1”。若有文本，请直接输出提取到的文本，不要输出任何与图中文本无关的内容, 请只返回图中的文字内容, 不要加其他任何描述。"),
    GRAPH_IMAGE("graph_image", """
            请分析图片，提取图中主要物体之间的 SPO 三元组。
            请以 JSON 数组格式返回，每个元素包含三个字段：
            - "s": Subject (主体，名词)
            - "p": Predicate (关系，如：位于、拿着、穿着、包含，动词/介词)
            - "o": Object (客体，名词)
            
            请输出 JSON 数组， 不要Markdown代码块，必须是中文。"""),
    GRAPH_TEXT("graph_text", """
            提取输入文字中的【实体关系】，并标准输出为 JSON 三元组。
            规则：
            - "s": Subject (主体，名词)
            - "p": Predicate (关系，如：位于、拿着、穿着、包含，动词/介词)
            - "o": Object (客体，名词)
           
           请输出 JSON 数组， 不要Markdown代码块，必须是中文。""");

    private final String type;
    private final String prompt;

    PromptEnum(String type, String prompt) {
        this.type = type;
        this.prompt = prompt;
    }

    public static String getPromptByType(String type) {
        for (PromptEnum value : values()) {
            if (value.getType().equals(type)) {
                return value.getPrompt();
            }
        }
        return DEFAULT.getPrompt();
    }
}
