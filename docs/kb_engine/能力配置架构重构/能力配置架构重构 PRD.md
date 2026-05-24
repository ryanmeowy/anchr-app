# 能力配置架构重构 PRD

> 版本: v1.0 | 日期: 2026-05-22 | 状态: 待评审

---

## 1. 问题陈述

### 1.1 现状

当前能力配置体系存在三个结构性问题：

**问题 A：云端能力与云服务商强绑定**

用户选择"能力提供商"(`app.capability-provider.* = aliyun`) 的同时，隐式绑定了 SDK、API 端点、和具体模型。模型名散落在各 Service 实现类内部，用户不可见也不可选。切换"提供商"等于同时切换三层，无渐进粒度。

```
app.capability-provider.gen = aliyun  →  DashScope SDK → qwen-plus (hardcode)
app.embedding.backend = aliyun       →  DashScope API  → multimodal-embedding-v1 (hardcode)
```

**问题 B：向量模型变更无联动**

`SettingsApiController` 已定义了 `requiresReindexFields = ["embeddingModel", "embeddingDimension", "chunkSize", "chunkOverlap"]`，`ProviderSettingServiceImpl` 切换时返回警告文案，但到此为止：

| 应触发 | 现状 |
|--------|------|
| Redis `search:vector:*` 缓存失效 | 未做，依赖 24h TTL |
| Redis `search:img:md5:*` 缓存失效 | 未做 |
| ES 索引维度变更 | 未做，`dense_vector dims` 建索引时写死 |
| 已有文档重索引 | 未做，仅 alias 机制预留了可能性 |

**问题 C：本地能力仅支持 gRPC Python**

所有本地能力 (`LocalGenService`、`LocalEmbeddingService`、`LocalOcrService`、`LocalCrossEncoderRerankService`) 统一通过 `vision.proto` 定义的 gRPC 协议与 Python 服务通信。这带来三个问题：

1. 运维成本高 — 多一个独立进程需要管理
2. 生态隔离 — Ollama / vLLM / llama.cpp 生态走 OpenAI-compatible HTTP API，需单独适配
3. 模型耦合 — 加能力要改 proto → 生成 stub → Python 端实现

**问题 D：冷启动依赖外部能力**

`@ConditionalOnProperty` 在启动时决定 Bean 创建。凭证未配时对应 Provider Bean 不存在，`ProviderRuntimeRegistry` 中缺失该条目，配置页无法感知"这个能力存在但未配置"。

---

## 2. 设计目标

1. **零依赖启动** — 首次启动无需配置任何外部凭证，所有能力以降级状态运行
2. **模型中心** — 用户选模型，运行时和凭证自动推导
3. **全量热更新** — 凭证、模型选择、运行时端点变更全部实时生效，无需重启
4. **模型变更联动** — embedding 模型变更自动清除向量缓存 + 触发重索引任务

---

## 3. 核心设计

### 3.1 三层解耦：模型 → 运行时 → 凭证组

```
用户选择        模型目录              运行时注册           凭证组
────────       ─────────             ─────────           ─────
           ┌─ model-catalog ─┐   ┌─ runtimes ──┐    ┌─ credentials ─┐
选择模型 ──→│ id              │   │ type: cloud │   ┌→│ access-key-id  │
           │ name            │──→│ endpoint    │──→│ access-key-secret│
           │ runtime ────────┘   │ cred-group ─┘   │ api-key        │
           │ dimension       │   └──────────────┘   └────────────────┘
           │ modalities      │
           └─────────────────┘
```

关联方向单向：`模型 → 运行时 → 凭证组`。用户只操作第一层。

### 3.2 YAML 配置结构

```yaml
app:
  # ── 模型目录 ──
  model-catalog:
    embedding:
      - id: qwen3-vl-embedding
        name: Qwen3 VL Embedding
        runtime: aliyun-dashscope
        dimension: 2560
        modalities: [text, image]
      - id: doubao-embedding-vision
        name: Doubao Embedding Vision
        runtime: volcengine-ark
        dimension: 2048
        modalities: [text, image]
      - id: bce-embedding-base
        name: BCEmbedding Base
        runtime: ollama
        dimension: 768
        modalities: [text]
    generation:
      - id: qwen-plus
        name: Qwen Plus
        runtime: aliyun-dashscope
      - id: qwen3:14b
        name: Qwen3 14B (本地)
        runtime: ollama
    rerank:
      - id: gte-rerank-v2
        name: GTE Rerank V2
        runtime: aliyun-dashscope
      - id: bce-reranker-base
        name: BCEmbedding Reranker Base
        runtime: ollama
    ocr:
      - id: qwen-vl-ocr
        name: Qwen VL OCR
        runtime: aliyun-dashscope
      - id: recognize-advanced
        name: 传统 OCR
        runtime: aliyun-ocr-sdk

  # ── 运行时注册 ──
  runtimes:
    aliyun-dashscope:
      type: cloud
      credential-group: aliyun
    aliyun-ocr-sdk:
      type: cloud
      credential-group: aliyun
    volcengine-ark:
      type: cloud
      credential-group: volcengine
    ollama:
      type: local
      endpoint: ${OLLAMA_ENDPOINT:http://localhost:11434}
      credential-group: none

  # ── 凭证组 ──
  credentials:
    aliyun:
      access-key-id: ${ALIYUN_ACCESS_KEY_ID:}
      access-key-secret: ${ALIYUN_ACCESS_KEY_SECRET:}
      dashscope-api-key: ${DASHSCOPE_API_KEY:}
    volcengine:
      access-key-id: ${VOLCENGINE_ACCESS_KEY_ID:}
      access-key-secret: ${VOLCENGINE_ACCESS_KEY_SECRET:}

  # ── 当前选择（运行时可变） ──
  active-models:
    embedding: ${APP_ACTIVE_EMBEDDING_MODEL:}
    generation: ${APP_ACTIVE_GENERATION_MODEL:}
    rerank: ${APP_ACTIVE_RERANK_MODEL:}
    ocr: ${APP_ACTIVE_OCR_MODEL:}
```

### 3.3 关联链路（以 embedding 为例）

```
active-models.embedding = "qwen3-vl-embedding"
        │
        ▼
model-catalog.embedding[id=qwen3-vl-embedding]
        │  dimension: 2560
        │  modalities: [text, image]
        │  runtime: aliyun-dashscope
        ▼
runtimes[aliyun-dashscope]
        │  type: cloud
        │  credential-group: aliyun
        ▼
credentials[aliyun]
        │  access-key-id: xxx
        │  dashscope-api-key: xxx
        ▼
AliyunDashScopeRuntime.embedText(text)
        │  使用 dashscope-api-key
        │  调用 /api/v1/services/embeddings/multimodal-embedding
        ▼
返回 List<Float> (2560维)
```

---

## 4. Provider 三态模型

### 4.1 状态定义

```java
public enum ProviderStatus {
    UNCONFIGURED,   // 凭证/端点未配置，不可调用
    CONFIGURING,    // 配置已保存，正在验证连接
    AVAILABLE,      // 可用
    DEGRADED,       // 曾经可用，当前异常（超时/限流/服务端不可达）
    UNAVAILABLE     // 明确不可用（凭证过期、额度耗尽）
}
```

### 4.2 状态流转

```
UNCONFIGURED ──凭证保存──→ CONFIGURING ──连接测试通过──→ AVAILABLE
     ↑                         │                              │
     │                    连接测试失败                   运行时异常
     │                         │                              │
     └─────────────────────────┘                        DEGRADED
                                                           │
                                                      重试成功
                                                           │
                                                           ↓
                                                       AVAILABLE
```

### 4.3 Provider Bean 生命周期

- **去除 `@ConditionalOnProperty`**：所有 Provider Bean 启动时全部创建
- **懒初始化**：SDK Client / gRPC Channel 在首次调用或凭证变更时才初始化
- **启动不抛异常**：凭证缺失、端点不可达均不阻断启动，仅标记 `UNCONFIGURED`
- **状态暴露**：每个 Provider Bean 实现 `getStatus()` 方法，注入 `ProviderRuntimeRegistry`

```java
@Service
public class AliyunGenerationService implements GenerationPort, ProviderIdentity {

    private volatile DashScopeClient client;
    private volatile ProviderStatus status = ProviderStatus.UNCONFIGURED;

    @EventListener
    public void onCredentialsChanged(CredentialChangedEvent event) {
        if (!event.matchesCredentialGroup("aliyun")) return;
        tryInitClient(event.getCredentials());
    }

    @Override
    public ProviderStatus getStatus() { return status; }

    @Override
    public String generate(String prompt) {
        if (status != ProviderStatus.AVAILABLE) {
            throw new ProviderNotAvailableException(
                "生成服务未配置。请前往设置页配置模型和凭证。"
            );
        }
        return client.call(prompt);
    }

    private void tryInitClient(CredentialGroup creds) {
        if (creds.dashscopeApiKey() == null) {
            this.status = ProviderStatus.UNCONFIGURED;
            return;
        }
        try {
            this.client = DashScopeClient.builder()
                .apiKey(creds.dashscopeApiKey())
                .build();
            this.status = ProviderStatus.AVAILABLE;
        } catch (Exception e) {
            log.warn("Failed to init Aliyun generation client", e);
            this.status = ProviderStatus.UNCONFIGURED;
        }
    }
}
```

---

## 5. 热更新机制

### 5.1 事件流

```
PATCH /api/v1/settings/providers/selection
  │  { "embedding": { "modelId": "bce-embedding-base" } }
  ▼
ProviderSettingService.switchProvider()
  │  1. ModelCatalogService.resolve(capability, modelId) → 校验模型存在
  │  2. 写入 app_setting 表
  │  3. 发布 ModelChangedEvent(capability, oldModelId, newModelId)
  ▼
┌─ ModelCatalogService.onModelChanged()
│     解析 newModel → runtime → credential-group
│     发布 RuntimeResolvedEvent
│     如果 embedding 变更 → 发布 ReindexRequiredEvent
│
├─ 各 Provider Bean.onRuntimeResolved()
│     检查 credential-group 是否匹配自己
│     匹配 → tryInitClient(credentials)
│     不匹配 → 忽略
│
├─ EmbeddingCacheManager.onModelChanged()
│     清除 Redis search:vector:*
│     清除 Redis search:img:md5:*
│
├─ ReindexOrchestrator.onReindexRequired()
│     创建新物理索引
│     批量重 embedding
│     alias 原子切换
│
└─ ProviderRuntimeRegistry.updateStatus()
      更新注册表中该 Provider 的状态
  ▼
返回前端: {
  effectiveImmediately: true,
  providerStatus: "AVAILABLE",
  warnings: []
}
```

### 5.2 热更新覆盖范围

| 变更类型 | 生效方式 | 额外动作 |
|----------|---------|---------|
| 切换模型（同运行时） | 即时 | 无 |
| 切换模型（跨运行时） | 即时 | 检查凭证，重试连接 |
| 更新凭证 | 即时 | Event → Provider Bean 重建 Client |
| 修改 Ollama 端点 | 即时 | Event → Ollama Bean 重连 |
| embedding 模型变更 | 即时 | 清除向量缓存 + 标记 reindex 待办 |
| embedding 维度变更 | 即时 | 新文档用新维度；旧文档显示"待重索引" |
| 添加新模型到 catalog | 需在 YAML 中添加 | 下次启动或配置重载后可选 |

---

## 6. 零依赖启动

### 6.1 启动时行为

| 资源 | 启动行为 |
|------|---------|
| ES 索引 | 默认维度 1024 创建；无 ES 连接则不建索引，日志 warn |
| Redis | 尝试连接；失败则跳过缓存，日志 warn |
| gRPC Channel | 不创建，首次调用时懒初始化 |
| 云端 SDK Client | 不创建，凭证事件触发后懒初始化 |
| Ollama HTTP Client | 不创建，`UNCONFIGURED` 状态 |

### 6.2 首次启动流程

```
Spring Boot 启动
  │
  ├─ 所有 Provider Bean 创建（状态全为 UNCONFIGURED）
  ├─ ProviderRuntimeRegistry 全量注册
  ├─ ES 索引尝试创建（默认维度 1024）
  ├─ Redis 尝试连接
  │
  └─ HTTP 服务监听 8080
        │
        ▼
  用户打开配置页
  GET /api/v1/settings/capabilities
        │
        ▼
  返回: [
    { capability: "embedding",  status: "UNCONFIGURED", availableModels: [...] },
    { capability: "generation", status: "UNCONFIGURED", availableModels: [...] },
    ...
  ]
        │
        ▼
  用户配置凭证 → 测试连接 → 选择模型 → 保存
        │
        ▼
  配置页显示所有能力 AVAILABLE
```

### 6.3 未配置能力的降级行为

| 能力 | 未配置时的降级 |
|------|--------------|
| Embedding | 文本检索回退为纯 BM25；图片检索/向量路不可用 |
| Generation | 回答生成不可用，搜索返回原始结果列表 |
| Rerank | 跳过 Rerank 阶段，直接输出 RRF 融合结果 |
| OCR | 图片入库跳过 OCR 文本提取，仅保留标题和文件名 |
| Object Storage | 上传文件走本地临时目录，预览使用 base64 data URI |

---

## 7. 模型变更联动

### 7.1 Embedding 模型变更

```
用户切换 embedding 模型
  │
  ├─ 即时：
  │   ├─ 清除 Redis search:vector:*
  │   ├─ 清除 Redis search:img:md5:*
  │   └─ 新入库文档使用新模型
  │
  ├─ 标记 reindex 待办：
  │   ├─ 前端显示警告横幅："Embedding 模型已变更，部分历史文档需重索引"
  │   └─ API 返回 requiresReindex: true, affectedAssetCount: 142
  │
  └─ 手动触发或自动：
      ├─ 用户点击"开始重索引"
      ├─ ReindexOrchestrator 创建新物理索引 (kb_segment_v2)
      ├─ 遍历所有 asset → 重新 embedding → 写入新索引
      ├─ alias kb_segment_read → kb_segment_v2（原子切换）
      └─ 删除旧索引
```

### 7.2 重索引期间的行为

- 读操作：走旧 alias，不受影响
- 写操作：双写旧索引和新索引，保证新文档立即可查
- 进度：前端轮询 `GET /api/v1/settings/reindex/status` 获取进度条

---

## 8. API 定义

### 8.1 能力概览

```
GET /api/v1/settings/capabilities

Response:
{
  "capabilities": [
    {
      "type": "embedding",
      "status": "AVAILABLE",
      "activeModel": {
        "id": "qwen3-vl-embedding",
        "name": "Qwen3 VL Embedding",
        "dimension": 2560,
        "modalities": ["text", "image"],
        "runtime": {
          "name": "aliyun-dashscope",
          "type": "cloud",
          "credentialConfigured": true
        }
      },
      "availableModels": [
        {
          "id": "qwen3-vl-embedding",
          "name": "Qwen3 VL Embedding",
          "dimension": 2560,
          "modalities": ["text", "image"],
          "runtimeName": "aliyun-dashscope",
          "runtimeType": "cloud",
          "selectable": true,
          "selectableReason": null
        },
        {
          "id": "bce-embedding-base",
          "name": "BCEmbedding Base",
          "dimension": 768,
          "modalities": ["text"],
          "runtimeName": "ollama",
          "runtimeType": "local",
          "selectable": false,
          "selectableReason": "Ollama 服务未连接"
        }
      ],
      "requiresReindex": false
    }
  ]
}
```

### 8.2 凭证配置

```
PUT /api/v1/settings/credentials/{credentialGroup}

Request:
{
  "accessKeyId": "xxx",
  "accessKeySecret": "xxx",
  "dashscopeApiKey": "xxx"
}

Response:
{
  "credentialGroup": "aliyun",
  "status": "AVAILABLE",
  "affectedCapabilities": ["embedding", "generation", "rerank", "ocr"],
  "providerStatuses": {
    "embedding": "AVAILABLE",
    "generation": "AVAILABLE",
    "rerank": "AVAILABLE",
    "ocr": "AVAILABLE"
  }
}
```

### 8.3 连接测试

```
POST /api/v1/settings/test-connection

Request:
{
  "credentialGroup": "aliyun",
  "capability": "generation",
  "testPrompt": "hello"
}

Response:
{
  "success": true,
  "latencyMs": 342,
  "providerStatus": "AVAILABLE"
}
```

### 8.4 模型选择

```
PATCH /api/v1/settings/models

Request:
{
  "embedding": "qwen3-vl-embedding",
  "generation": "qwen-plus",
  "rerank": "gte-rerank-v2",
  "ocr": "qwen-vl-ocr"
}

Response:
{
  "effectiveImmediately": true,
  "warnings": [
    "Embedding 模型变更，建议重索引 142 篇已有文档"
  ],
  "reindexRequired": true,
  "affectedAssetCount": 142
}
```

### 8.5 重索引

```
POST /api/v1/settings/reindex

Response:
{
  "taskId": "reindex_20260522_001",
  "status": "IN_PROGRESS",
  "totalAssets": 142,
  "completedAssets": 0,
  "estimatedMinutes": 15
}

GET /api/v1/settings/reindex/{taskId}/status

Response:
{
  "taskId": "reindex_20260522_001",
  "status": "IN_PROGRESS",
  "totalAssets": 142,
  "completedAssets": 87,
  "failedAssets": 2,
  "estimatedMinutes": 5
}
```

---

## 9. 新增 Java 类清单

| 类 | 包 | 职责 |
|----|-----|------|
| `ModelCatalogProperties` | `.settings.config` | `@ConfigurationProperties("app.model-catalog")` |
| `RuntimeProperties` | `.settings.config` | `@ConfigurationProperties("app.runtimes")` |
| `CredentialGroupProperties` | `.settings.config` | `@ConfigurationProperties("app.credentials")` |
| `ActiveModelProperties` | `.settings.config` | `@ConfigurationProperties("app.active-models")` |
| `ModelCatalogService` | `.settings.application` | 模型 → 运行时 → 凭证 解析 |
| `ProviderStatus` | `.settings.domain.model` | 三态枚举 |
| `CredentialChangedEvent` | `.settings.domain.event` | 凭证变更事件 |
| `ModelChangedEvent` | `.settings.domain.event` | 模型切换事件 |
| `ReindexRequiredEvent` | `.settings.domain.event` | 重索引请求事件 |
| `ReindexOrchestrator` | `.settings.application` | 重索引编排（新索引创建 → 批量重 embedding → alias swap） |
| `EmbeddingCacheManager` | `.search.application` | 向量缓存清除 |
| `ProviderConnectionTestService` | `.settings.application` | 连接测试（复用现有逻辑） |

---

## 10. 实施计划

### M1：配置层重构（3-4 天）

- 新增 `model-catalog`、`runtimes`、`credentials` YAML 结构
- 实现 `ModelCatalogProperties`、`RuntimeProperties`、`CredentialGroupProperties`、`ActiveModelProperties`
- 实现 `ModelCatalogService`
- 改造 `ProviderSelectionService` 从直接读 provider 名字 → 走 ModelCatalogService
- 向后兼容：保留旧配置项的解析，标记 `@Deprecated`

### M2：Provider 生命周期改造（2-3 天）

- 新增 `ProviderStatus` 枚举
- 所有 Provider Bean 去除 `@ConditionalOnProperty`，新增懒初始化
- 每个 Provider 实现 `getStatus()` + `@EventListener`
- 改造 `ProviderRuntimeRegistry` 支持运行时状态更新
- 实现 `ProviderConnectionTestService`

### M3：零依赖启动（1-2 天）

- ES 索引初始化改为容错模式
- Redis 连接改为容错模式
- gRPC Channel 改为懒初始化
- 各能力未配置时的降级路径实现
- 启动日志增加能力状态汇总

### M4：热更新 + 联动（2-3 天）

- 实现 `CredentialChangedEvent` / `ModelChangedEvent` / `ReindexRequiredEvent`
- 实现 `EmbeddingCacheManager`（Redis 向量缓存清除）
- 实现 `ReindexOrchestrator`
- Settings REST API 新增端点（PUT credentials、PATCH models、POST reindex）
- 前端配置页 API 适配

### M5：Ollama Runtime（1-2 天）

- 新增 `OllamaRuntimeService`（HTTP REST，OpenAI-compatible 端点）
- 实现 `EmbeddingPort`、`GenerationPort`、`RerankPort`
- YAML 新增 `runtime: ollama` 模型条目
- 技术验证：Ollama 本地环境连接 + embedding 效果测试

总计：约 9-14 人天。

---

## 11. 风险

| 风险 | 概率 | 缓解 |
|------|------|------|
| 旧配置项迁移不完全导致生产启动失败 | 中 | M1 阶段保留旧配置兼容，加集成测试 |
| 懒初始化导致首次调用延迟过高 | 低 | 首次调用后 client 复用；可加预热接口 |
| Ollama embedding 质量不如云端模型 | 中 | 保留云端选项，Ollama 作为可选 Runtime |
| Reindex 期间双写导致 ES 写入压力 | 低 | 控制并发度，小批量处理 |
| 多 Runtime 并存时凭证管理混乱 | 低 | 凭证按 credential-group 分组，与 Runtime 松耦合 |
