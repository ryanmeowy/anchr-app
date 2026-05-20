package com.anchr.core.integration.constant;

public class AliyunConstant {

    public static final String DEFAULT_REGION = "cn-shanghai";

    public static final String DEFAULT_ROLE_SESSION_NAME = "frontend-upload";

    public static final String EMBEDDING_MODEL_NAME = "multimodal-embedding-v1";

    public static final String VISION_MODEL_NAME = "qwen-vl-plus";

    public static final String IMAGE_GEN_MODEL_NAME = "qwen-vl-max";

    public static final String X_OSS_PROCESS_EMBEDDING = "image/resize,l_2048,m_lfit/format,jpg/quality,q_75";

    public static final Long DEFAULT_STS_DURATION_SECONDS = 15 * 60L;

    public static final String TEXT_MODEL_NAME = "qwen-plus";

    public static final String RERANK_MODEL_NAME = "gte-rerank-v2";

    public static final String BAILIAN_API_KEY_ENV_NAME = "DASHSCOPE_API_KEY";
}
