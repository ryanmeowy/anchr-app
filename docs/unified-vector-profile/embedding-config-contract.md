# Embedding 配置契约

本项目 embedding backend 只由 `app.embedding.backend` 决定，`local`、`cloud-aliyun`、`cloud-volcengine` 等 Spring profile 不再隐式切换 embedding 能力。

默认决策：

- backend：`aliyun`
- model：`multimodal-embedding-v1`
- dimension：`1024`
- preprocess-version：`v1`
- image-input-mode：`url`

配置入口：

```yaml
app:
  embedding:
    backend: ${APP_EMBEDDING_BACKEND:aliyun}
    model: ${APP_EMBEDDING_MODEL:multimodal-embedding-v1}
    dimension: ${APP_EMBEDDING_DIMENSION:1024}
    preprocess-version: ${APP_EMBEDDING_PREPROCESS_VERSION:v1}
    image-input-mode: ${APP_EMBEDDING_IMAGE_INPUT_MODE:url}
```

约束：

- `app.capability-provider.embedding` 已废弃，不再参与 embedding backend 选择。
- `app.vector.dimension` 与 `app.kb-segment.dimension` 必须与 `app.embedding.dimension` 一致。
- `smart_gallery_*` 与 `kb_segment_*` 继续使用独立物理索引，索引名后缀统一包含 `backend-model-dimension-preprocessVersion`。
- 本地和云端环境需要通过环境变量提供默认 backend 所需凭证，例如默认阿里云 backend 需要 `DASHSCOPE_API_KEY`。
