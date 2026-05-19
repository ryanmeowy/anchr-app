# 全局唯一 Embedding Provider 与双索引链路保留技术方案

## 背景

当前项目通过 `app.capability-provider.embedding` 在不同 Spring profile 中切换 embedding 实现：

```yaml
app:
  capability-provider:
    embedding: aliyun
```

对应实现类直接实现业务端口：

- `AliyunMultiModelEmbeddingService implements SearchEmbeddingPort, IngestionEmbeddingPort`
- `VolcengineEmbeddingService implements SearchEmbeddingPort, IngestionEmbeddingPort`
- `LocalEmbeddingService implements SearchEmbeddingPort, IngestionEmbeddingPort`

这种模式让 local/cloud 环境很容易使用不同 embedding provider，进一步导致向量维度、模型、索引 profile 和缓存 key 分裂。

本阶段目标是：**embedding 能力从环境 profile 中解耦，收敛为全局唯一 provider**。也就是说，不管当前启动的是 local、cloud-aliyun 还是 cloud-volcengine profile，只要全局配置的 embedding backend 相同，入库、检索、向量比较都走同一个 embedding backend。

同时，当前两条索引链路先保留：

- legacy 图片索引链路：`smart_gallery_*`，向量字段为 `imageEmbedding`。
- 统一知识库段索引链路：`kb_segment_*`，向量字段为 `embedding`。

## 目标

- 移除 embedding 能力基于 `app.capability-provider.embedding` 的环境切换。
- 业务侧只装配一个统一 embedding provider。
- 底层 embedding backend 通过全局配置选择：`aliyun`、`volcengine` 或 `local`。
- local/cloud 启动 profile 不再隐式决定 embedding backend。
- `smart_gallery_*` 和 `kb_segment_*` 两条索引链路继续保留，各用各的索引。
- 两条链路使用同一个 embedding backend、model、dimension、preprocessVersion。
- 保持现有 REST API、Streamlit 页面和主要检索服务不做行为性迁移。

## 非目标

- 不下线 `smart_gallery_*`。
- 不合并 `smart_gallery_*` 与 `kb_segment_*`。
- 不把 `/api/v1/vision/*` 迁移到 `UnifiedSearchService`。
- 不改变 `SearchEmbeddingPort` 和 `IngestionEmbeddingPort` 的业务语义。
- 不兼容旧模型、旧维度、旧 backend 下产生的向量数据。
- 不在本阶段实现全量回填工具。

## 核心设计

### 分层模型

目标结构：

```text
SearchEmbeddingPort / IngestionEmbeddingPort
              |
              v
UnifiedEmbeddingProvider
              |
              v
EmbeddingBackend
  |------------|---------------|
  v            v               v
Aliyun       Volcengine       Local
```

业务域只依赖：

- `SearchEmbeddingPort`
- `IngestionEmbeddingPort`

业务域不感知底层 backend 是阿里云、火山引擎还是本地模型。

### 配置模型

新增或收敛为如下配置：

```yaml
app:
  embedding:
    backend: aliyun
    model: multimodal-embedding-v1
    dimension: 1024
    preprocess-version: v1
    image-input-mode: url
```

字段含义：

- `backend`：全局唯一 embedding backend，可选 `aliyun`、`volcengine`、`local`。
- `model`：当前 backend 使用的模型名。
- `dimension`：该模型输出向量维度。
- `preprocess-version`：图片和文本预处理版本。
- `image-input-mode`：图片输入策略，保留现有语义，例如 `url`、`auto`。

`app.capability-provider.embedding` 不再使用。其他能力仍保留现有 provider 切换：

```yaml
app:
  capability-provider:
    rerank: aliyun
    gen: aliyun
    ocr: local
    object-storage: aliyun
```

### Provider 与 Backend 的边界

本方案中的命名约定：

- `UnifiedEmbeddingProvider`：业务侧唯一 provider，负责实现 `SearchEmbeddingPort` 和 `IngestionEmbeddingPort`。
- `EmbeddingBackend`：集成层内部后端接口，负责实际调用阿里云、火山引擎或本地 gRPC。

这样可以避免业务侧出现多个 embedding provider Bean，也避免 local/cloud profile 影响 embedding 选择。

## 当前链路保持方式

### 写入链路

图片入库继续双写：

```text
ImageIngestionServiceImpl
  -> embeddingPort.embedImage(...)
  -> EsBatchTemplate.bulkSave(successDocs)      写 smart_gallery_*
  -> ImageSegmentIndexWriter.write(doc)         写 kb_segment_*
```

文本入库继续写 `kb_segment_*`：

```text
TextAssetIngestionServiceImpl
  -> embeddingPort.embedText(...)
  -> TextSegmentIndexWriter.save(...)
  -> KbSegmentBulkWriter.write(...)
```

变化点只有一个：`embeddingPort` 背后永远是 `UnifiedEmbeddingProvider`，再由它按 `app.embedding.backend` 委托到底层 backend。

### 查询链路

legacy 图片搜索继续走：

```text
SearchApiController /api/v1/vision/*
  -> SearchServiceImpl
  -> RetrievalStrategy
  -> ImageSearchRepository / ImageRepository
  -> smart_gallery_* / imageEmbedding
```

知识库搜索继续走：

```text
KbSearchApiController /api/v1/search/kb
  -> UnifiedSearchServiceImpl
  -> KbQueryEmbeddingService
  -> KbSegmentRepository
  -> kb_segment_* / embedding
```

变化点同样只有 embedding 生成来源统一。

## 代码改动设计

### 新增 `EmbeddingProperties`

建议新增统一配置类：

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {
    private String backend;
    private String model;
    private Integer dimension;
    private String preprocessVersion = "v1";
    private String imageInputMode = "auto";
}
```

用途：

- `UnifiedEmbeddingProvider` 读取 `backend` 做委托选择。
- `VectorConfig` 和 `KbSegmentConfig` 读取 `backend/model/dimension/preprocessVersion` 生成 vector profile。
- 启动健康检查校验 dimension 与索引 mapping。

### 新增 `EmbeddingBackend`

建议新增内部接口：

```java
public interface EmbeddingBackend {
    String backendName();

    List<Float> embedText(String text);

    List<Float> embedImage(String imageInput);

    List<Float> embedImage(byte[] imageBytes, String contentType);
}
```

位置建议：

```text
src/main/java/com/smart/vision/core/integration/multimodal/embedding/EmbeddingBackend.java
```

### 新增 `UnifiedEmbeddingProvider`

统一业务侧 provider：

```java
@Service
@RequiredArgsConstructor
public class UnifiedEmbeddingProvider implements SearchEmbeddingPort, IngestionEmbeddingPort {

    private final EmbeddingProperties properties;
    private final List<EmbeddingBackend> backends;

    @Override
    public List<Float> embedText(String text) {
        return selectedBackend().embedText(text);
    }

    @Override
    public List<Float> embedImage(String imageInput) {
        return selectedBackend().embedImage(imageInput);
    }

    @Override
    public List<Float> embedImage(byte[] imageBytes, String contentType) {
        return selectedBackend().embedImage(imageBytes, contentType);
    }

    private EmbeddingBackend selectedBackend() {
        // 根据 app.embedding.backend 选择唯一 backend；未命中或重复时 fail fast
    }
}
```

位置建议：

```text
src/main/java/com/smart/vision/core/integration/multimodal/embedding/UnifiedEmbeddingProvider.java
```

### 改造现有厂商实现

当前：

```java
AliyunMultiModelEmbeddingService implements SearchEmbeddingPort, IngestionEmbeddingPort
VolcengineEmbeddingService implements SearchEmbeddingPort, IngestionEmbeddingPort
LocalEmbeddingService implements SearchEmbeddingPort, IngestionEmbeddingPort
```

目标：

```java
AliyunEmbeddingBackend implements EmbeddingBackend
VolcengineEmbeddingBackend implements EmbeddingBackend
LocalEmbeddingBackend implements EmbeddingBackend
```

改造方式：

- 保留原有 manager 调用逻辑。
- 去掉 `SearchEmbeddingPort` 和 `IngestionEmbeddingPort` 实现。
- 去掉 `@ConditionalOnProperty(prefix = "app.capability-provider", name = "embedding", ...)`。
- 可选择全部 backend Bean 都装配，运行时由 `UnifiedEmbeddingProvider` 根据 `app.embedding.backend` 选择。
- 如果某 backend 的 SDK 配置缺失，应在被选中时 fail fast，未被选中时不阻塞启动。

### Vector profile 生成规则

当前 `VectorConfig` 和 `KbSegmentConfig` 读取：

```java
@Value("${app.capability-provider.embedding:unknown}")
private String embeddingProvider;

@Value("${app.embedding.model:unknown}")
private String embeddingModel;

@Value("${app.embedding.preprocess-version:v1}")
private String preprocessVersion;
```

目标改为：

```java
@Value("${app.embedding.backend:unknown}")
private String embeddingBackend;

@Value("${app.embedding.model:unknown}")
private String embeddingModel;

@Value("${app.embedding.preprocess-version:v1}")
private String preprocessVersion;
```

profile 仍保持现有语义：

```text
backend + model + dimension + preprocessVersion
```

区别是：`backend` 现在是全局配置，不再由 local/cloud profile 隐式切换。

示例：

```text
aliyun-multimodal-embedding-v1-1024-v1
```

两条链路索引各自生成：

```text
smart_gallery_v3__aliyun-multimodal-embedding-v1-1024-v1
kb_segment_v2__aliyun-multimodal-embedding-v1-1024-v1
```

### 配置收敛

`application.yaml` 中保留 embedding 全局配置默认值：

```yaml
app:
  embedding:
    backend: aliyun
    model: multimodal-embedding-v1
    dimension: 1024
    preprocess-version: v1
    image-input-mode: url
```

`application-local.yaml` 不再覆盖 embedding backend，除非本地开发明确要全局切到 local：

```yaml
app:
  vector:
    index-name: smart_gallery
    indexVersion: v3
    read-alias: smart_gallery_read
    write-alias: smart_gallery_write
    dimension: 1024
  kb-segment:
    index-name: kb_segment
    indexVersion: v2
    read-alias: kb_segment_read
    write-alias: kb_segment_write
    dimension: 1024
```

`application-cloud-aliyun.yaml`、`application-cloud-volcengine.yaml` 只配置各自云能力参数，不再通过 `capability-provider.embedding` 选择 embedding。

如果需要切换 embedding backend，使用全局配置或环境变量：

```bash
APP_EMBEDDING_BACKEND=local
APP_EMBEDDING_MODEL=clip-vit-b32
APP_EMBEDDING_DIMENSION=512
APP_EMBEDDING_PREPROCESS_VERSION=v1
```

## 涉及文件

### 必改代码

- `src/main/java/com/smart/vision/core/integration/multimodal/service/cloud/aliyun/AliyunMultiModelEmbeddingService.java`
  - 改为 backend 实现或拆出 `AliyunEmbeddingBackend`。
- `src/main/java/com/smart/vision/core/integration/multimodal/service/cloud/volcengine/VolcengineEmbeddingService.java`
  - 改为 backend 实现或拆出 `VolcengineEmbeddingBackend`。
- `src/main/java/com/smart/vision/core/integration/multimodal/service/local/LocalEmbeddingService.java`
  - 改为 backend 实现或拆出 `LocalEmbeddingBackend`。
- `src/main/java/com/smart/vision/core/common/config/VectorConfig.java`
  - profile 使用 `app.embedding.backend`。
- `src/main/java/com/smart/vision/core/common/config/KbSegmentConfig.java`
  - profile 使用 `app.embedding.backend`。

### 新增代码

- `EmbeddingProperties`
- `EmbeddingBackend`
- `UnifiedEmbeddingProvider`
- 可选：`EmbeddingBackendRegistry`，用于选择和校验 backend。

### 必改配置

- `src/main/resources/application.yaml`
  - 去掉或停止使用 `app.capability-provider.embedding`。
  - 增加 `app.embedding.backend/model/dimension/preprocess-version`。
- `src/main/resources/application-local.yaml`
  - 不再配置 local 专属 embedding provider。
  - `app.vector.dimension` 与 `app.kb-segment.dimension` 对齐全局 embedding dimension。
- `src/main/resources/application-cloud-aliyun.yaml`
  - 不再配置 cloud 专属 embedding provider。
  - `app.vector.dimension` 与 `app.kb-segment.dimension` 对齐全局 embedding dimension。
- `src/main/resources/application-cloud-volcengine.yaml`
  - 同上。

### 建议改

- `src/main/java/com/smart/vision/core/search/escli/domain/EsCliAccessControl.java`
  - 默认 allowlist 同时包含 `VectorConfig` 和 `KbSegmentConfig`。
- 启动健康检查
  - 校验选中的 backend 存在。
  - 校验 vector/kb dimension 与 `app.embedding.dimension` 一致。
  - 校验当前索引 mapping dims 与配置一致。

### 暂不改

- `SearchServiceImpl`
- `UnifiedSearchServiceImpl`
- `ImageIngestionServiceImpl`
- `ImageSegmentIndexWriter`
- `TextSegmentIndexWriter`
- `SearchApiController`
- `KbSearchApiController`
- Streamlit 页面

## 数据与索引影响

### 索引

如果统一 backend/model/dimension 后与旧配置不同，需要新建两套索引版本：

```text
smart_gallery_v3__aliyun-multimodal-embedding-v1-1024-v1
kb_segment_v2__aliyun-multimodal-embedding-v1-1024-v1
```

旧索引不能原地复用，尤其是以下变化：

- backend 变化。
- model 变化。
- dimension 变化。
- preprocess-version 变化。

### 缓存

当前缓存 key 使用 vector profile：

```text
search:vector:{profile}:{textHash}
search:img:md5:{profile}:{imageMd5}
compare:text:{profile}:{textHash}
compare:image:{profile}:{imageMd5}
```

切换 backend/model/dimension/preprocessVersion 后，profile 变化，旧缓存自然失效。不做旧 key 兼容。

### 回填

历史数据需要按新 backend 重新生成向量：

- legacy 图片索引重新生成 `imageEmbedding`。
- `kb_segment` 重新生成 `embedding`。

新增数据在配置切换后会通过双写链路进入两套新索引。

## 影响面

### 正向影响

- embedding 选择不再受 local/cloud profile 影响。
- 入库、检索、向量比较使用同一个 embedding backend。
- 继续保留两条索引链路，避免一次性迁移风险。
- 后续下线 legacy 图片索引时，向量空间已经统一，迁移难度降低。

### 行为影响

- 如果默认 backend 选云端，本地开发也会调用云端 embedding。
- 如果默认 backend 选本地，云端部署必须能访问本地 embedding 服务。
- 切换 backend/model/dimension 会产生新索引和新缓存 key。
- 检索分数分布可能变化，需要重新校准阈值。

### API 影响

本阶段不改变以下接口 contract：

- `/api/v1/vision/search`
- `/api/v1/vision/search-page`
- `/api/v1/vision/search-by-image`
- `/api/v1/vision/similar`
- `/api/v1/vision/vector-compare`
- `/api/v1/search/kb`

## 验证方案

### 单元测试

- 只装配一个 `SearchEmbeddingPort` Bean。
- 只装配一个 `IngestionEmbeddingPort` Bean。
- `UnifiedEmbeddingProvider` 能按 `app.embedding.backend` 选择正确 backend。
- 未配置 backend 或 backend 不存在时 fail fast。
- `VectorConfig#getVectorProfile()` 使用 `app.embedding.backend`。
- `KbSegmentConfig#getVectorProfile()` 使用 `app.embedding.backend`。
- `app.vector.dimension`、`app.kb-segment.dimension`、`app.embedding.dimension` 不一致时校验失败。

### 集成验证

- local Spring profile 下设置 `app.embedding.backend=aliyun`，确认实际调用阿里云 embedding。
- cloud Spring profile 下设置 `app.embedding.backend=aliyun`，确认 profile 和向量维度一致。
- 切换 `app.embedding.backend=local` 后，local/cloud 都走本地 backend。
- 图片入库后两条索引都有数据。
- 文本入库后 `kb_segment` 有数据。
- `/api/v1/vision/search-by-image` 和 `/api/v1/search/kb` 均可检索。

### 回归范围

- 图片上传入库。
- 文本上传入库。
- 图片搜索分页。
- 图搜图。
- 相似图。
- 向量比较。
- KB 搜索。
- 多轮对话检索。
- ES CLI 查询两套索引。

## 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 云端环境无法访问本地 backend | embedding 调用失败 | 生产默认使用云端 backend，或部署可访问的统一 embedding 服务 |
| 本地开发无法访问云端 backend | 本地调试受阻 | 支持通过环境变量全局切换 `app.embedding.backend=local` |
| 未选中的 backend 配置缺失导致启动失败 | 无法在轻量环境启动 | backend 配置校验仅对被选中 backend 强制执行 |
| 多个业务侧 embedding provider Bean 同时存在 | Spring 注入冲突 | 厂商实现不再直接实现 `SearchEmbeddingPort`/`IngestionEmbeddingPort` |
| 旧索引数据未回填 | 新索引召回不完整 | 新建索引版本并制定回填 runbook |
| 分数阈值漂移 | 检索质量波动 | 对核心 query 集做阈值校准 |

## 推进顺序

1. 明确全局默认 embedding backend、model、dimension、preprocessVersion。
2. 引入 `EmbeddingProperties`、`EmbeddingBackend`、`UnifiedEmbeddingProvider`。
3. 将阿里云、火山、本地 embedding 实现改为 backend。
4. 移除 embedding 对 `app.capability-provider.embedding` 的依赖。
5. 调整 `VectorConfig`、`KbSegmentConfig` 使用 `app.embedding.backend` 生成 profile。
6. 统一 local/cloud 配置，升级两条索引版本。
7. 增加单元测试与启动校验。
8. 小批量新索引写入与检索验证。
9. 校准阈值。
10. 制定全量回填与 alias 切换 runbook。

## 验收标准

- 业务侧只有一个 `SearchEmbeddingPort` Bean。
- 业务侧只有一个 `IngestionEmbeddingPort` Bean。
- local/cloud profile 不再决定 embedding backend。
- `app.embedding.backend` 是唯一 embedding backend 选择入口。
- `VectorConfig` 与 `KbSegmentConfig` 生成的 profile 使用同一个 backend/model/dimension/preprocessVersion。
- `smart_gallery_*` 与 `kb_segment_*` 使用相同向量维度。
- 两条链路 API 均保持可用。
- 新写入图片仍能双写两套索引。
- 新写入文本仍进入 `kb_segment`。
