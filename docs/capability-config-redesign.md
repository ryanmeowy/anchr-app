# 能力配置重构方案

## 目标

将 Embedding / Generation / Rerank 三个 AI 能力的配置从环境变量 + 厂商 SDK 模式
迁移为**前端输入 baseUrl + apiKey + modelName，测试通过后后端直接使用**的通用模式。

---

## 一、现状

```
配置来源: application.yaml 环境变量占位符
          ↓ capability-provider.{gen,rerank,ocr,object-storage}
运行时:    ProviderSelectionService → ProviderRuntimeRegistry
          → 厂商 Bean (@ConditionalOnProperty) → 厂商 SDK

embedding: 阿里云 DashScope SDK (MultiModalEmbedding) / 火山引擎 Ark SDK
generation: 阿里云 DashScope SDK (Generation)
rerank:    阿里云 DashScope SDK (Rerank)
```

**问题**：切换厂商需要改环境变量 + 重启；配置散落在 yaml / env / DB 三处；`provider_setting` 表形同虚设。

---

## 二、目标架构

```
配置来源: capability_config 表 (前端 Settings 页面写入)
          ↓ CapabilityConfigService 读取
运行时:    通用 HTTP 客户端 (baseUrl + apiKey + modelName)
          → POST {baseUrl}/v1/embeddings (或 /chat/completions 等)
          → 标准 OpenAI 兼容格式, 不依赖厂商 SDK
```

**优势**：前端配置即时生效，无需重启；去掉所有厂商 SDK 依赖；配置集中在一张表。

---

## 三、数据模型

### 3.1 新表 `capability_config`

```sql
create table capability_config (
  id              varchar(64) primary key,
  capability      varchar(32) not null,      -- EMBEDDING | GENERATION | RERANK
  base_url        varchar(512) not null,     -- OpenAI-compatible endpoint
  api_key_enc     varchar(512) not null,     -- AES-256-CBC 加密存储
  model_name      varchar(128),              -- 文本模型名
  image_model     varchar(128),              -- 图片 embedding 模型 (仅 EMBEDDING)
  image_endpoint  varchar(512),              -- 图片 embedding 专用路径 (仅 EMBEDDING)
  enabled         boolean not null default true,
  updated_by      varchar(64) not null default 'system',
  updated_at      timestamp not null,
  unique key uk_capability (capability)
);
```

### 3.2 各能力填充规则

| 字段 | EMBEDDING | GENERATION | RERANK |
|---|---|---|---|
| `base_url` | ✅ | ✅ | ✅ |
| `api_key_enc` | ✅ | ✅ | ✅ |
| `model_name` | ✅ `text-embedding-v4` | ✅ `qwen-plus` | ✅ `gte-rerank-v2` |
| `image_model` | ✅ 可选 | — | — |
| `image_endpoint` | ✅ 可选 | — | — |

### 3.3 OCR 和 OSS

OCR 和 OBJECT_STORAGE **不纳入此表**。它们不是 OpenAI-compatible 接口，配置形状不同（ak/sk + endpoint + bucket），暂时保持环境变量注入。

---

## 四、Rest API

### 4.1 读取配置

```
GET /api/v1/settings/embedding
→ { baseUrl, modelName, imageModel, imageEndpoint, apiKeyMasked: "sk-***b8", enabled }

GET /api/v1/settings/generation
→ { baseUrl, modelName, apiKeyMasked: "sk-***b8", enabled }

GET /api/v1/settings/rerank
→ 同上
```

`apiKey` 不返回明文，返回脱敏值（前4后4，中间 `***`）。

### 4.2 保存配置

```
PATCH /api/v1/settings/embedding
body: { baseUrl, apiKey, modelName, imageModel, imageEndpoint }

PATCH /api/v1/settings/generation
body: { baseUrl, apiKey, modelName }

PATCH /api/v1/settings/rerank
body: { baseUrl, apiKey, modelName }
```

`apiKey` 在前端可空（表示不改），后端收到非空值时 AES 加密后更新 `api_key_enc`。

### 4.3 测试连接

```
POST /api/v1/settings/embedding/test
body: { baseUrl, apiKey, modelName, imageModel, imageEndpoint }

POST /api/v1/settings/generation/test
body: { baseUrl, apiKey, modelName }

POST /api/v1/settings/rerank/test
body: { baseUrl, apiKey, modelName }
```

**不存入 DB**，仅用传入参数做一次真实调用：

- EMBEDDING: `POST {baseUrl}/embeddings` body `{ model, input: "test" }`，检查返回是否包含 `data[0].embedding`
- GENERATION: `POST {baseUrl}/chat/completions` body `{ model, messages: [{role:"user",content:"hi"}] }`，检查 `choices[0].message.content`
- RERANK: `POST {baseUrl}/rerank` body `{ model, query: "test", documents: ["test"] }`，检查 `results`

返回 `{ success: bool, latencyMs: long, message: string }`。不返回 `apiKey`。

---

## 五、后端改动清单

### 5.1 新增

| 文件 | 说明 |
|---|---|
| `capability_config` 表 | Flyway 迁移 |
| `CapabilityConfig` | 领域模型 (record/value object) |
| `CapabilityConfigRepository` | 领域仓库接口 |
| `MyBatisCapabilityConfigRepository` | MyBatis 实现 |
| `CapabilityConfigMapper` + `Record` + `.xml` | 持久层 |
| `CapabilityConfigService` | 应用服务 (读写 + 加解密 + 测试连接) |
| `SettingsApiController` 新增端点 | GET|PATCH /embedding, /generation, /rerank, POST /{cap}/test |
| `GenericEmbeddingClient` | 通用 HTTP embedding 客户端 |
| `GenericGenerationClient` | 通用 HTTP generation 客户端 |
| `GenericRerankClient` | 通用 HTTP rerank 客户端 |

### 5.2 删除

| 文件/目录 | 说明 |
|---|---|
| `AliyunMultiModelEmbeddingService.java` | 阿里云 embedding SDK 实现 |
| `VolcengineEmbeddingService.java` | 火山引擎 embedding SDK 实现 |
| `UnifiedEmbeddingProvider.java` | embedding 路由代理 |
| `EmbeddingBackend.java` | embedding 后端接口 |
| `EmbeddingBackendRegistry.java` | embedding 后端注册 |
| `BailianEmbeddingManager.java` | 百炼 embedding SDK 封装 |
| `VolcengineEmbeddingManager.java` | 火山 embedding SDK 封装 |
| `AliyunGenService.java` | 阿里云 generation SDK 实现 |
| `AliyunGenManager.java` | 百炼 generation SDK 封装 |
| `GenerationProviderRouter.java` | generation 路由代理 |
| `AliyunCrossEncoderRerankService.java` | 阿里云 rerank SDK 实现 |
| `RerankProviderRouter.java` | rerank 路由代理 |
| `OcrProviderRouter.java` | OCR 路由代理 |
| `ObjectStorageProviderRouter.java` | OSS 路由代理 |
| `ProviderRuntimeRegistry.java` | 运行时厂商注册表 |
| `ProviderIdentity.java` | 厂商身份接口 |
| `ProviderSelectionService.java` | 厂商选择服务 |
| `ProviderSettingServiceImpl.java` | 厂商配置服务 |
| `ProviderSetting.java` + `Repository` + `Record` + `Mapper` + `.xml` | 厂商配置持久层 |
| `ProviderConfigVersionRepository` + `Mapper` + `.xml` | 厂商配置版本历史 |
| `provider_setting` 表 | Flyway 迁移删除 |
| `provider_config_version` 表 | Flyway 迁移删除 |
| `ProviderType.java` | 厂商类型枚举 |
| `CapabilityProviderProperties.java` | 厂商配置属性 |
| `CapabilitiesDTO.java` | 能力概览 DTO |
| `SettingsQueryServiceImpl.java` | 能力查询服务 |
| `ProviderSwitchResult.java` | 厂商切换结果 |
| `ProviderSwitchRequestDTO` / `ResultDTO` | 厂商切换 DTO |
| `ProviderConnectionTestProperties.java` | 连接测试配置 (已删) |
| `application.yaml` 中 `capability-provider` 段 | 厂商配置 |
| `application.yaml` 中 `embedding.backend` 等 | embedding 后端选择 |
| pom.xml 中 DashScope / Ark SDK 依赖 | 厂商 SDK |

### 5.3 修改

| 文件 | 变更 |
|---|---|
| `KbIngestionTaskProcessorImpl` | 注入 `GenericEmbeddingClient` 替代 `IngestionEmbeddingPort` |
| `UnifiedSearchServiceImpl` | 注入 `GenericRerankClient` 替代 `SearchRerankPort` |
| `ConversationMessagePipeline` 链路上的 generation 调用 | 注入 `GenericGenerationClient` |
| `IngestionEmbeddingPort` / `SearchEmbeddingPort` | 合并为 `EmbeddingPort` (或直接删掉，用客户端替代) |
| `SearchRerankPort` | 删除，用 `GenericRerankClient` |
| `ConversationRewritePort` / `SearchContentPort` / `IngestionContentPort` | 删除，用 `GenericGenerationClient` |
| `SettingsApiController` | 新增 embedding/generation/rerank 端点，删除 /capabilities, /providers, /providers/selection |

---

## 六、前端改动

### 6.1 Settings 页面

当前页面只做**只读展示**，需改为可编辑表单：

```
┌─ 模型配置 ──────────────────────────────────────────────┐
│                                                          │
│  生成模型                                                │
│  ┌──────────────────────────────────────────────────┐   │
│  │ Base URL  [https://dashscope.aliyuncs.com/com...] │   │
│  │ API Key   [sk-********************************]   │   │
│  │ Model     [qwen-plus__________________________]   │   │
│  │                  [测试连接]  ● 连通 (238ms)      │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  嵌入模型                                                │
│  ┌──────────────────────────────────────────────────┐   │
│  │ Base URL  [____________________________________] │   │
│  │ API Key   [____________________________________] │   │
│  │ Model     [text-embedding-v4___________________] │   │
│  │ 图片模型  [tongyi-embedding-vision-plus________] │   │
│  │ 图片接口  [/api/v1/services/embeddings/_______]  │   │
│  │                  [测试连接]                       │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  重排序模型                                              │
│  ┌──────────────────────────────────────────────────┐   │
│  │ ...                                              │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│                              [保存全部]                  │
└──────────────────────────────────────────────────────────┘
```

### 6.2 新增 API 调用

```ts
// api-client.ts
getEmbeddingConfig: () => request<CapabilityConfig>("/api/v1/settings/embedding"),
updateEmbeddingConfig: (body) => request(..., { method: "PATCH", body }),
testEmbedding: (body) => request(..., { method: "POST", body }),

// generation / rerank 同理
```

### 6.3 删除

- `providers` API 调用 (`GET /api/v1/settings/providers`)
- `provider` / `providers` 相关类型定义
- OSS 配置表单保留（`localStorage` 方案不变）

---

## 七、实施顺序

| 阶段 | 内容 | 风险 |
|---|---|---|
| **1. 新建** | `capability_config` 表 + 完整的 CRUD 服务 + 测试连接 API | 低，纯增量 |
| **2. 新建** | `GenericEmbeddingClient` / `GenericGenerationClient` / `GenericRerankClient` | 低，纯增量 |
| **3. 切换** | `KbIngestionTaskProcessorImpl` 切换注入 `GenericEmbeddingClient` | 中 |
| **4. 切换** | generation / rerank 链路切换注入通用客户端 | 中 |
| **5. 清理** | 删除所有旧 provider 体系代码 + 厂商 SDK 依赖 | 低 |
| **6. 前端** | Settings 页面改造 | 低 |

建议阶段 1 和 2 先做完，验证测试连接 API 对阿里云和火山引擎都能通过，再做 3-5 的切换和清理。

---

## 八、附带清理：`workspace_id` 全量移除

workspace 体系已删除，项目单用户模式，`workspace_id` 永远等于 `"default"`。

### 涉及的表

| 表 | 列 | 索引 |
|---|---|---|
| `knowledge_base` | `workspace_id` | `idx_kb_workspace_status` |
| `document_asset` | `workspace_id` | — |
| `ingestion_task` | `workspace_id` | — |
| `ingestion_task_item` | (JOIN 条件中) | — |
| `activity_event` | `workspace_id` | `idx_activity_user_created` |
| `app_setting` | `workspace_id` | `uk_app_setting_workspace_key` |

### 代码层影响

| 层 | 改动 |
|---|---|
| Domain Model | KnowledgeBase / DocumentAsset / IngestionTask / IngestionTaskItem / AppSetting / ActivityEvent — 去掉 `workspaceId` 字段 |
| Repository 接口 | 所有方法签名去掉 `workspaceId` 参数 |
| Mapper + XML | 去掉列映射、WHERE 条件、JOIN 条件中的 `workspace_id` |
| `RequestUserContext` | `role` 去掉后只剩 `userId`，可以讨论是否直接传 `userId` 替代 ThreadLocal |
| Application Service | 去掉 `context.workspaceId()` → `repository.xxx(context.workspaceId(), ...)` 变成 `repository.xxx(...)` |
| `UserContextHolder.systemDefault()` | 简化 |

### 处理建议

在阶段 5 (清理) 中统一处理，或单独开一轮。新表 `capability_config` 不引入 `workspace_id`。

---

## 九、OSS 对象存储配置

### 9.1 现状问题

前端 `localStorage` 存了 OSS 配置（bucket / endpoint / prefix / encryptKey / encryptIv），用于浏览器端 OSS SDK 直传。`AuthApiController.sts()` 签发 AES 加密的 STS 临时凭证，前端解密后使用。

问题：
- 配置存在浏览器端，换设备丢失
- `encryptKey` / `encryptIv` 暴露给前端
- 无法后端统一管理

### 9.2 新流程

```
前端                          后端
  │                             │
  │  GET /api/v1/auth/sts       │
  │─────────────────────────────→ 读 storage_config 表
  │                             │ 调 STS AssumeRole
  │  { endpoint, bucket,        │
  │    accessKeyId,             │
  │    accessKeySecret,         │
  │    securityToken,           │
  │    region, prefix }         │
  │←─────────────────────────────
  │                             │
  │  用临时凭证 + endpoint       │
  │  直传 OSS (浏览器端)        │
  │─────────────────────────────→ OSS
```

前端零配置——调一次 sts 接口拿到所有信息，用完即弃。后端从 `storage_config` 表读取凭证，签 STS 返回。

### 9.3 表结构

```sql
create table storage_config (
  id              varchar(64) primary key,
  capability      varchar(32) not null default 'OBJECT_STORAGE',
  endpoint        varchar(512) not null,     -- https://oss-cn-hangzhou.aliyuncs.com
  access_key_enc  varchar(512) not null,     -- AES
  secret_key_enc  varchar(512) not null,     -- AES
  bucket          varchar(256) not null,
  region          varchar(64),               -- nullable, S3 兼容
  prefix          varchar(256),              -- 对象 key 前缀，如 "anchr-dev/"
  role_arn        varchar(256),              -- STS AssumeRole ARN
  enabled         boolean not null default true,
  updated_by      varchar(64),
  updated_at      timestamp,
  unique key (capability)
);
```

### 9.4 API

```
GET  /api/v1/settings/storage       → 读取配置 (ak/sk 脱敏)
PATCH /api/v1/settings/storage       → 保存配置
POST  /api/v1/settings/storage/test  → 测试: 尝试 AssumeRole + ListObjects

GET  /api/v1/auth/sts                → 前端上传用, 返回临时凭证 + endpoint + bucket
                                      (替代当前从 localStorage 读 ossEndpoint/ossBucket)
```

### 9.5 与 AI 能力的区别

| | AI 能力 (EMBEDDING/GEN/RERANK) | OSS |
|---|---|---|
| 协议 | OpenAI-compatible HTTP API | S3 / STS |
| 凭证模型 | 长期 apiKey，后端持有 | 长期 ak/sk 存后端 → 签 STS 短期凭证给前端 |
| 前端感知 | 零，全部走后端 | 拿临时凭证直传 |
| 表 | `capability_config` | `storage_config` |
| 配置字段 | baseUrl / apiKey / modelName | endpoint / ak / sk / bucket / roleArn |

### 9.6 前端清理

- 删除 `local-settings.ts` 中 OSS 配置相关代码
- 删除 `encryptKey` / `encryptIv` 前端解密逻辑（STS 返回明文，走 HTTPS 即可）
- 删除 `validateOssConfigOrThrow` 等客户端校验
- `imports-page.tsx` 上传前调 `GET /api/v1/auth/sts` 获取临时凭证 + bucket/endpoint
