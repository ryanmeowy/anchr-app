# QAnything 方向产品化补齐 PRD

## 1. 背景

当前项目已经具备知识库 RAG 的核心技术链路：文件入库、OCR、Embedding、Elasticsearch 混合检索、RRF 融合、rerank、多轮对话、证据引用与预览定位。

但从产品形态看，当前能力更接近“检索与问答引擎”，还没有形成 QAnything 类产品需要的完整闭环：

- 用户可以创建和管理知识库。
- 用户可以把不同格式资料上传到某个知识库。
- 用户可以查看文档解析、向量化、索引状态。
- 用户可以在指定知识库范围内问答和搜索。
- 用户可以点击答案引用，定位到原文、页码、表格行、图片 bbox。
- 用户可以配置模型、OCR、对象存储等运行能力。

本 PRD 基于当前项目已实现接口和《qanything方向能力补齐方案.md》，定义 React/Next.js 正式版前端所需的后端能力补齐范围。

## 2. 产品目标

### 2.1 核心目标

将当前系统从“技术验证型检索系统”升级为“可独立使用的知识库问答产品”。

用户主路径：

```text
创建知识库 -> 上传资料 -> 查看入库状态 -> 对话问答 / 关键词检索 -> 点击引用预览原文 -> 调整配置
```

### 2.2 设计原则

- 知识库是顶层操作对象，用户不直接感知底层 ES index 或 kb_segment。
- 文档资产是管理对象，segment 是检索和证据定位对象。
- 问答和搜索必须支持知识库范围。
- 回答必须可追溯到证据位置。
- 前端只负责展示、交互和轻量缓存，不承载检索、排序、任务编排等核心业务逻辑。

## 3. 存储与账号策略

### 3.1 业务 DB

正式实现本 PRD 时需要接入业务 DB。

原因：

- 知识库、文档资产、入库任务、设置等对象属于产品主数据，需要可靠持久化。
- ES 适合搜索和向量召回，不适合作为业务主库。
- Redis 适合缓存、锁、短期 token 和临时状态，不适合长期保存产品对象。
- 仅靠当前任务状态和 ES 文档无法支撑文档管理、删除、reparse、reembed、统计、审计等能力。

建议优先使用 PostgreSQL 或 MySQL。若第一阶段强调本地单机部署和低门槛，可支持 SQLite 作为开发/个人版存储；正式部署仍建议使用 PostgreSQL/MySQL。

### 3.2 存储职责边界

业务 DB 负责：

```text
knowledge_base
document_asset
ingestion_task
ingestion_task_item
app_setting / provider_setting
user / workspace / membership（预留）
```

ES 负责：

```text
kb_segment
BM25 检索
向量检索
anchor / bbox / snippet 检索字段
```

Redis 负责：

```text
短期 token
任务锁
任务执行中的瞬时状态
previewUrl 缓存
热点数据缓存
```

会话数据可分阶段处理：

- 第一阶段：保留当前 Redis/现有会话存储策略，降低改造量。
- 第二阶段：将 `conversation_session`、`conversation_turn` 迁入 DB，支持长期历史、多用户、审计和备份。

### 3.3 建议核心表

第一阶段至少需要：

```text
knowledge_base
document_asset
ingestion_task
ingestion_task_item
```

第二阶段建议补充：

```text
conversation_session
conversation_turn
app_setting
provider_setting
user
workspace
workspace_member
```

### 3.4 DB 表结构设计

详细表结构已迁移至：[qanything方向DB表结构设计.md](./qanything方向DB表结构设计.md)。

### 3.5 账号与 SSO 策略

第一版不建议直接接入 SSO，除非当前目标就是公司内部多人正式使用。

推荐演进路径：

```text
P0：单管理员 token / 本地单用户
P1：本地账号密码 + 用户表
P2：SSO / OIDC / 企业微信 / 飞书 / LDAP
```

原因：

- 当前更核心的缺口是 DB、知识库模型、文档管理、`kbId` 范围检索和设置页。
- SSO 会引入用户体系、组织、权限、回调、安全配置和部署复杂度，容易拖慢主线。
- QAnything 方向第一阶段的重点是“资料丢进去就能问”，不是企业 IAM。

### 3.6 权限模型预留

即使第一版不接 SSO，数据模型也应预留未来多用户和企业认证能力。

建议预留：

```text
User
Workspace
KnowledgeBase
WorkspaceMember
Role: OWNER / ADMIN / EDITOR / VIEWER
```

核心业务表建议预留字段：

```text
workspaceId
createdBy
updatedBy
ownerId
```

第一版可使用固定系统用户或管理员用户填充这些字段，避免后续接入账号体系时大规模迁移。

## 4. 页面范围

React/Next.js 正式版包含 6 个页面：

| 页面 | 路由 | 定位 | 当前后端状态 |
|---|---|---|---|
| 知识库 | `/` 或 `/kbs` | 知识库创建、切换、统计、最近活动 | 缺失 |
| 对话问答 | `/conversations` | 多轮知识库问答、引用证据、Trace | 基本可接 |
| 关键词检索 | `/search` | 传统检索入口，精确查证 | 可接基础版 |
| 文档管理 | `/kbs/[kbId]/documents` | 上传、文档列表、入库任务、失败重试 | 部分具备 |
| 设置 | `/settings` | 模型、OCR、对象存储、检索参数配置 | 缺失 |
| 预览 | `/preview/[segmentId]` | PDF/TXT/MD/IMAGE 原文预览与定位 | 基本可接 |

## 5. 当前能力评估

### 5.1 已具备

#### 对话问答

已有接口：

```text
POST   /api/conversations
GET    /api/conversations
GET    /api/conversations/{sessionId}
PATCH  /api/conversations/{sessionId}
DELETE /api/conversations/{sessionId}
POST   /api/conversations/{sessionId}/messages
GET    /api/conversations/{sessionId}/messages
```

已有字段：

- `answer`
- `citations`
- `resultCards`
- `retrievalTrace`
- `suggestedQuestions`

可支撑：

- 创建会话
- 会话列表
- 发送消息
- 历史消息恢复
- Top3 结果卡片
- 基础 Trace 展示

#### 关键词检索

已有接口：

```text
POST /api/v1/search/kb
```

已有字段：

- `segmentId`
- `assetId`
- `segmentType`
- `assetType`
- `snippet`
- `score`
- `pageNo`
- `anchor`
- `thumbnail`
- `ocrSummary`
- `totalHits`
- `topChunks`

可支撑：

- 输入 query 搜索
- 展示混合检索结果
- 展示文本、图片、PDF 命中摘要
- 点击 `segmentId` 进入预览

#### 预览

已有接口：

```text
GET /api/v1/preview/segments/{segmentId}
```

已有字段：

- `previewUrl`
- `expiresAt`
- `anchor`
- `surroundingChunks`
- `snippet`
- `ocrSummary`
- `thumbnail`

可支撑：

- PDF/TXT/MD/IMAGE 预览
- 图片 bbox 绘制
- 文本周边 chunk 展示
- preview URL 过期后重新请求

#### 入库任务

已有接口：

```text
POST /api/v1/ingestion/text-assets/batch-tasks
GET  /api/v1/ingestion/text-assets/batch-tasks/{taskId}
POST /api/v1/ingestion/text-assets/batch-tasks/{taskId}/items/{itemId}/retry
POST /api/v1/ingestion/text-assets/batch-tasks/{taskId}/retry-failed

POST /api/v1/image/batch-tasks
GET  /api/v1/image/batch-tasks/{taskId}
POST /api/v1/image/batch-tasks/{taskId}/items/{itemId}/retry
POST /api/v1/image/batch-tasks/{taskId}/retry-failed
```

可支撑：

- 文本和图片上传后提交批处理任务
- 查询单个任务状态
- 单项重试
- 失败项批量重试

### 5.2 主要缺口

| 模块 | 缺口 |
|---|---|
| 知识库 | 无 KnowledgeBase CRUD、统计、归档、删除 |
| 文档管理 | 无 DocumentAsset 列表、详情、删除、reparse、reembed |
| 入库任务 | 无按知识库聚合的任务列表；文本/图片任务接口未统一 |
| 搜索 | 无 `kbId` 范围；筛选、分页、facets 不完整 |
| 对话 | 无多知识库范围；无 SSE；回答模式未产品化 |
| Ask First 体验 | 无首页聚合、最近问题、最近引用、搜索页生成答案、引用解释等体验型 API |
| 设置 | 无配置读取、连接测试、模型配置、OCR/对象存储配置 API |
| 文档格式 | 当前主要支持 PDF/TXT/MD/IMAGE，DOCX/XLSX/CSV/PPTX/URL 仍需扩展 |

## 6. 领域模型

### 6.1 KnowledgeBase

知识库是顶层资源。

建议字段：

| 字段 | 说明 |
|---|---|
| `kbId` | 知识库 ID |
| `name` | 名称 |
| `description` | 描述 |
| `status` | `ACTIVE` / `ARCHIVED` / `DELETING` |
| `documentCount` | 文档数量 |
| `segmentCount` | segment 数量 |
| `lastIngestionStatus` | 最近入库状态 |
| `createdAt` / `updatedAt` | 创建和更新时间 |
| `workspaceId` | 所属工作区，第一版可固定默认值 |
| `createdBy` / `updatedBy` | 创建人和更新人，第一版可固定系统用户 |

### 6.2 DocumentAsset

文档资产是用户上传或导入的一份资料。

建议字段：

| 字段 | 说明 |
|---|---|
| `assetId` | 文档 ID |
| `kbId` | 所属知识库 |
| `fileName` | 原始文件名 |
| `title` | 展示标题 |
| `fileType` | `PDF` / `DOCX` / `XLSX` / `IMAGE` / `URL` 等 |
| `mimeType` | MIME 类型 |
| `objectKey` | 对象存储 key |
| `sourceUrl` | URL 导入来源 |
| `fileHash` | 文件 hash |
| `sizeBytes` | 文件大小 |
| `parseStatus` | 解析状态 |
| `indexStatus` | 索引状态 |
| `segmentCount` | segment 数量 |
| `embeddingProfile` | embedding profile |
| `errorMessage` | 失败原因 |
| `createdAt` / `updatedAt` | 创建和更新时间 |
| `workspaceId` | 所属工作区，第一版可固定默认值 |
| `createdBy` / `updatedBy` | 创建人和更新人，第一版可固定系统用户 |

### 6.3 IngestionTask

入库任务承载批量上传、解析、向量化、索引过程。

建议字段：

| 字段 | 说明 |
|---|---|
| `taskId` | 任务 ID |
| `kbId` | 目标知识库 |
| `status` | `PENDING` / `RUNNING` / `SUCCESS` / `PARTIAL_SUCCESS` / `FAILED` |
| `totalCount` | 总文件数 |
| `successCount` | 成功数 |
| `failureCount` | 失败数 |
| `runningCount` | 运行中数量 |
| `items` | 每个文件的任务项 |
| `createdAt` / `updatedAt` | 创建和更新时间 |
| `createdBy` / `updatedBy` | 创建人和更新人，第一版可固定系统用户 |

### 6.4 Segment

Segment 是检索和回答的最小证据单元。

建议字段：

| 字段 | 说明 |
|---|---|
| `segmentId` | segment ID |
| `kbId` | 所属知识库 |
| `assetId` | 所属文档 |
| `segmentType` | `TEXT_CHUNK` / `IMAGE_OCR_BLOCK` / `TABLE_ROW_GROUP` / `PPT_SLIDE` |
| `contentText` | 正文 |
| `headingPath` | 标题路径 |
| `pageNumber` | PDF/PPT 页码 |
| `sheetName` | Excel sheet |
| `rowRange` / `columnRange` | 表格定位 |
| `bbox` | 图片/PDF 区域定位 |
| `sourceOrder` | 原文顺序 |
| `prevSegmentId` / `nextSegmentId` | 相邻片段 |
| `embeddingProfile` | 向量模型 profile |

## 7. 页面需求

### 7.1 知识库页

#### 目标

用户进入系统后可以查看、创建、切换和管理知识库。

#### 功能需求

- 查看知识库列表。
- 创建知识库。
- 重命名知识库。
- 删除或归档知识库。
- 查看知识库统计：
  - 文档数
  - segment 数
  - 最近入库任务状态
  - 最近更新时间
- 展示 Ask First 首页需要的聚合信息：
  - 常用或收藏知识库
  - 最近提问
  - 最近引用来源
  - 导入进度摘要
- 点击知识库进入文档管理或问答。

#### 后端需求

```text
POST   /api/v1/kbs
GET    /api/v1/kbs
GET    /api/v1/kbs/{kbId}
PATCH  /api/v1/kbs/{kbId}
DELETE /api/v1/kbs/{kbId}
GET    /api/v1/kbs/{kbId}/stats

GET    /api/v1/home/summary
GET    /api/v1/activity/recent-questions
GET    /api/v1/activity/recent-citations
```

`GET /api/v1/home/summary` 用于 Ask First 风格首页聚合，建议返回：

```text
favoriteKbs
recentQuestions
recentCitations
recentIngestionTasks
helpLinks
```

第一版如果不做收藏能力，`favoriteKbs` 可以按最近使用或更新时间返回。

#### 验收标准

- 用户可以创建一个知识库并在列表中看到。
- 用户可以切换当前知识库。
- 删除或归档后，该知识库不再出现在默认可用列表中。
- 统计字段为空或计算中时，前端有明确占位态。
- 首页聚合接口缺少部分数据时，前端可以降级为空状态，不影响核心问答入口。

### 7.2 文档管理页

#### 目标

用户可以把资料上传到指定知识库，并查看每个文档的解析、向量化和索引状态。

#### 功能需求

- 批量上传文档。
- URL 导入入口。
- 展示支持格式、单文件大小上限和单批次数量上限。
- 导入时支持选择去重策略。
- 查看文档列表。
- 查看文档详情。
- 查看失败原因。
- 删除文档。
- 对失败文档重试。
- 对文档执行重新解析 `reparse`。
- 对文档执行重新向量化 `reembed`。
- 查看入库任务列表和任务详情。

#### 后端需求

```text
POST   /api/v1/kbs/{kbId}/documents
GET    /api/v1/kbs/{kbId}/documents
GET    /api/v1/kbs/{kbId}/documents/{assetId}
DELETE /api/v1/kbs/{kbId}/documents/{assetId}
POST   /api/v1/kbs/{kbId}/documents/{assetId}/reparse
POST   /api/v1/kbs/{kbId}/documents/{assetId}/reembed

POST   /api/v1/kbs/{kbId}/ingestion-tasks
GET    /api/v1/kbs/{kbId}/ingestion-tasks
GET    /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}
POST   /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed

GET    /api/v1/ingestion/capabilities
```

`GET /api/v1/ingestion/capabilities` 用于前端导入页渲染能力边界，建议返回：

```text
supportedFormats
maxFileSizeBytes
maxFilesPerBatch
dedupeStrategies
ingestionStages
```

`ingestionStages` 至少包含：

```text
UPLOAD
PARSE
CHUNK
EMBED
INDEX
ASKABLE
```

导入任务项建议补充：

```text
stage
progress
dedupeResult
assetId
```

#### 兼容策略

第一阶段可以在后端内部复用现有：

- `/api/v1/ingestion/text-assets/batch-tasks`
- `/api/v1/image/batch-tasks`

但对前端暴露应逐步收敛到 `/api/v1/kbs/{kbId}/ingestion-tasks`，避免前端感知文本/图片两套任务体系。

#### 验收标准

- 上传文件后能看到任务状态。
- 每个文档能展示解析状态、索引状态、segment 数。
- 失败文档能看到明确错误原因。
- 删除文档后，搜索和问答不再命中该文档。
- `reparse` / `reembed` 操作会生成新的任务或状态流转。
- 导入页能展示当前支持格式和限制，超过限制时前端可在提交前拦截。
- 同一文件重复导入时，用户能看到跳过、覆盖或新版本等去重结果。

### 7.3 对话问答页

#### 目标

用户可以在一个或多个知识库范围内进行多轮问答，并查看答案引用证据。

#### 功能需求

- 创建会话。
- 查看会话列表。
- 切换会话。
- 重命名/删除会话。
- 发送问题。
- 展示回答。
- 展示引用卡片。
- 展示推荐追问。
- 支持知识库范围选择。
- 支持回答模式：
  - 严格问答
  - 总结模式
  - 对比模式
  - 仅检索
  - 调试模式
- 右侧面板切换：
  - 引用来源
  - 检索过程
  - 原文预览
  - 调试 JSON

#### 已有接口

```text
POST   /api/conversations
GET    /api/conversations
GET    /api/conversations/{sessionId}
PATCH  /api/conversations/{sessionId}
DELETE /api/conversations/{sessionId}
POST   /api/conversations/{sessionId}/messages
GET    /api/conversations/{sessionId}/messages
```

#### 待补字段

请求侧：

```text
kbIds
answerMode
preferredModalities
debug
stream
```

响应侧：

```text
kbScope
answerMode
retrievalStage
```

#### SSE 需求

建议新增：

```text
POST /api/conversations/{sessionId}/messages/stream
```

事件：

```text
event: trace
event: delta
event: citations
event: done
event: error
```

#### 验收标准

- 刷新页面后会话历史可以恢复。
- 回答卡片点击可以进入预览页。
- 历史消息的 `resultCards` 从 turn 快照读取，不二次检索。
- 无证据时默认不编造回答，并展示降级原因。
- 开发者模式默认关闭，开启后可查看 Trace。

### 7.4 关键词检索页

#### 目标

提供传统搜索入口，满足精确查证、快速定位和非问答式检索场景。

#### 功能需求

- 输入关键词搜索。
- 选择知识库范围。
- 按文件类型筛选。
- 按时间筛选。
- 按命中类型筛选。
- 支持“生成答案”模式，用于 Ask First 风格搜索页在结果上方给出综合回答。
- 展示结果列表。
- 展示 result score、source、snippet、pageNo、bbox 状态。
- 点击结果进入预览页。
- “联网搜索”开关默认隐藏或置灰，除非后端明确接入外部搜索 provider。

#### 已有接口

```text
POST /api/v1/search/kb
```

若搜索页需要直接生成答案，建议二选一：

```text
POST /api/v1/search/kb-answer
```

或在现有搜索接口请求中增加：

```text
withAnswer
answerMode
```

#### 待补字段

请求侧：

```text
kbIds
assetTypes
dateRange
hitTypes
cursor
sort
withAnswer
answerMode
```

响应侧：

```text
nextCursor
facets
total
answer
citations
answerTrace
```

#### 验收标准

- 用户可以在指定知识库范围内搜索。
- 每条结果都能展示来源信息。
- 有 `segmentId` 的结果可以进入预览。
- 图片命中有 bbox 时显示“可定位”状态。
- 开启生成答案时，答案必须绑定引用证据；无法生成时降级展示检索结果。
- 联网搜索未接入前不展示真实可用状态，避免前端承诺不存在的能力。
- 无结果、请求失败、超时都有明确状态。

### 7.5 预览页

#### 目标

用户点击引用或搜索结果后，可以定位到原文证据位置。

#### 功能需求

- 根据 `segmentId` 拉取预览信息。
- 支持 PDF 页码定位。
- 支持 TXT/MD snippet 定位。
- 支持 IMAGE bbox 绘制。
- 支持 surrounding chunks 展示。
- 支持解释“为什么引用这段”。
- 支持 preview URL 过期后自动重新请求一次。
- 支持复制引用。
- 支持返回来源页面。

#### 已有接口

```text
GET /api/v1/preview/segments/{segmentId}
```

#### 待补能力

可选增强：

```text
GET  /api/v1/preview/segments/{segmentId}/neighbors
POST /api/v1/preview/segments/{segmentId}/refresh
```

也可以先复用现有 GET 接口作为 refresh。

Ask First 引用解释建议在预览响应中补充：

```text
sourceQuestion
answerClaim
citationIndex
citationReason
```

其中 `citationReason` 用于解释该片段与答案结论的关系。第一版可以由检索命中类型、重排分数、关键词覆盖和 LLM 引用映射生成，无法稳定生成时返回空，由前端隐藏该区域。

#### 验收标准

- `previewUrl` 不在前端持久化，不写入日志。
- 401/403/过期场景自动重新请求一次。
- bbox 缺失、越界或尺寸异常时不画框，展示 OCR/命中文本降级。
- PDF 跳页失败时给出明确提示。
- 引用解释缺失时不影响原文预览和引用复制。

### 7.6 设置页

#### 目标

降低部署和联调门槛，支持查看和测试模型、OCR、对象存储、检索参数配置。

#### 功能需求

- 查看当前 provider。
- 测试模型连接。
- 测试 OCR 连接。
- 测试对象存储连接。
- 查看检索参数。
- 修改可热更新的检索参数。
- 支持 light/dark/system 外观偏好。
- 对需要重启或重建索引的配置给出明确提示。

#### 后端需求

```text
GET   /api/v1/settings/capabilities
GET   /api/v1/settings/providers
GET   /api/v1/settings/search
PATCH /api/v1/settings/search
POST  /api/v1/settings/test-connection
GET   /api/v1/settings/preferences
PATCH /api/v1/settings/preferences
```

第一阶段不建议支持所有配置热切换。策略：

| 配置类型 | 第一版策略 |
|---|---|
| 检索阈值、RRF 参数、rerank window | 可支持热更新 |
| chunk size / overlap | 新入库生效，旧文档需 reparse |
| generation model | 可先重启生效 |
| embedding model | 需要 reembed |
| embedding dimension | 需要新索引 + 全量 reembed |
| OCR provider | 第一版重启生效 |
| object storage provider | 第一版重启生效 |
| Redis / ES 连接 | 不建议页面热切 |

[外观偏好第一版可优先保存在浏览器本地。只有需要跨设备同步时，才接入 `settings/preferences` 或用户偏好表。

]()#### 验收标准

- 用户能看到当前使用的 provider。
- 连接测试失败时能看到明确原因。
- 修改会影响旧数据的配置时，前端必须提示 reparse/reembed/reindex。
- 不允许在页面上明文展示完整 API Key。

### 7.7 Ask First 专属体验能力

#### 目标

Ask First 方向强调“先问、再看证据、再回到资料”，页面不应只像管理后台。后端需要提供少量体验型聚合 API，避免前端在多个基础接口之间拼装首页、最近活动和引用上下文。

#### 需要补齐的能力

| 能力 | 建议接口 | 优先级 | 说明 |
|---|---|---|---|
| 首页聚合 | `GET /api/v1/home/summary` | P0 | 返回常用知识库、最近问题、最近引用、导入进度摘要 |
| 最近问题 | `GET /api/v1/activity/recent-questions` | P1 | 支撑 Ask 首页和会话入口，可先由 conversation history 派生 |
| 最近引用 | `GET /api/v1/activity/recent-citations` | P1 | 支撑“最近查过的证据”，需要记录 citation click 或 answer citation 快照 |
| 搜索生成答案 | `POST /api/v1/search/kb-answer` | P1 | 支撑搜索页顶部综合答案，也可并入 `/api/v1/search/kb` |
| 导入能力声明 | `GET /api/v1/ingestion/capabilities` | P0 | 返回支持格式、大小限制、批次数量、去重策略、阶段枚举 |
| 引用解释 | 预览响应补充字段 | P1 | 返回 `sourceQuestion`、`answerClaim`、`citationReason` |
| 联网搜索 | `POST /api/v1/search/web` | P2 | 未接入 provider 前前端不展示或置灰 |

#### 数据记录要求

为支撑最近活动和引用上下文，建议在 DB 中补充或复用以下记录：

```text
conversation_turn
conversation_citation
activity_event
user_preference（可选）
```

`activity_event` 可作为轻量事件表，记录：

```text
QUESTION_ASKED
CITATION_OPENED
DOCUMENT_IMPORTED
SEARCH_EXECUTED
```

第一版如果不想新增独立事件表，可以先从 `conversation_turn`、`ingestion_task`、`document_asset` 中派生首页聚合数据；但引用点击、最近引用这类行为数据后续仍建议落表。

#### 验收标准

- Ask 首页可以通过一个聚合接口完成首屏渲染。
- 最近问题和最近引用为空时，有稳定空状态，不阻断提问。
- 搜索页生成答案失败时，仍返回可用检索结果。
- 导入页展示的格式、大小、数量限制来自后端，不在前端硬编码。
- 引用解释属于增强信息，缺失时前端隐藏，不影响预览主流程。

## 8. 文档格式能力

### 8.1 当前能力

已有或基本具备：

- PDF
- TXT
- Markdown
- IMAGE

### 8.2 待补优先级

| 优先级 | 格式 | 原因 |
|---|---|---|
| P0 | DOCX | 企业文档、说明书、方案最常见 |
| P0 | XLSX / CSV | 表格知识库高频，且差异化明显 |
| P1 | PPTX | 培训材料、销售材料、技术方案常见 |
| P1 | HTML / URL | 支持网页知识库和在线文档导入 |
| P2 | ZIP | 批量导入体验 |

### 8.3 解析要求

| 格式 | 应保留的信息 |
|---|---|
| PDF | 页码、段落、标题、表格、图片/OCR 坐标 |
| DOCX | heading 层级、段落、表格、列表、页内图片 |
| XLSX | sheet、表头、行列、单元格坐标、合并单元格 |
| CSV | 表头、行号、列名、分隔符识别 |
| PPTX | slide 页码、标题、正文、备注、图片 OCR |
| HTML | title、h1-h6、正文块、代码块、链接上下文 |
| 图片 | OCR block、bbox、原图尺寸、纠错后文本 |

## 9. 非功能需求

### 9.1 权限与安全

- 所有前端请求统一通过 API Client 注入 token/header。
- `previewUrl` 不持久化、不写日志。
- API Key 不明文返回。
- 删除知识库和删除文档需要二次确认。
- 第一版可使用管理员 token 或本地单用户认证。
- SSO 不进入 P0 范围，但核心表必须预留 `workspaceId`、`createdBy`、`updatedBy`、`ownerId`。
- P2 支持 OIDC / 企业微信 / 飞书 / LDAP 等 SSO 能力。

### 9.2 性能

- 对话页面首屏 P95 < 2.5s。
- 预览接口 P95 < 800ms。
- 文档列表支持分页。
- 搜索结果支持分页或 cursor。

### 9.3 可观测

建议记录事件：

```text
conversation_send
result_card_click
preview_open_success
preview_open_fail
document_upload
ingestion_retry
settings_test_connection
```

### 9.4 状态体验

所有页面必须有：

- loading
- empty
- error
- unauthorized
- retry

## 10. 优先级

### P0：前端可用闭环

- 接入业务 DB。
- 新增知识库、文档资产、入库任务核心表。
- KnowledgeBase CRUD 基础版。
- DocumentAsset 列表和状态。
- 文档上传到指定知识库。
- 搜索和问答支持 `kbIds`。
- 预览页稳定接入。
- Ask 首页聚合接口基础版。
- 导入能力声明接口。
- 统一 API Client、鉴权、错误态。
- 管理员 token / 本地单用户认证。

### P1：QAnything 产品体验

- DOCX / XLSX / CSV。
- 文档删除。
- reparse / reembed。
- SSE 流式问答。
- 回答模式。
- 最近问题、最近引用。
- 搜索页生成答案。
- 预览页引用解释。
- 设置页连接测试。
- URL 导入。
- 本地账号密码 + 用户表。

### P2：成熟产品能力

- PPTX / ZIP。
- Provider Router 热切换。
- 配置版本回滚。
- 审计日志。
- 多用户/权限模型。
- 联网搜索 provider。
- SSO / OIDC / 企业微信 / 飞书 / LDAP。

## 11. 推荐实施顺序

### Phase 1：知识库产品模型

- 接入 PostgreSQL/MySQL 或开发期 SQLite。
- 新增 KnowledgeBase / DocumentAsset / IngestionTask 产品模型。
- 搜索和问答支持 `kbId` 范围。
- 文档列表支持状态、失败原因、删除、重试。
- 补齐首页聚合和导入能力声明接口。
- 暂不接 SSO，使用管理员 token / 本地单用户。

### Phase 2：React/Next.js 正式版闭环

- 完成 6 个页面静态布局。
- 接入 API Client。
- 对话、搜索、预览完成真实联调。
- 文档管理接入任务和文档列表。
- Ask First 首页、搜索生成答案、引用解释按能力状态做降级接入。

### Phase 3：文档格式扩展

- DOCX。
- XLSX / CSV。
- URL 导入。

### Phase 4：结构化 Chunk 与证据定位增强

- heading path。
- 表格 chunk。
- surrounding chunks。
- reparse / reembed 闭环。

### Phase 5：部署与模型可插拔

- OpenAI-compatible generation。
- Ollama / vLLM / LM Studio。
- 本地 embedding / rerank / OCR 服务。
- 配置页和连接测试。

### Phase 6：账号与企业集成

- 本地用户体系。
- Workspace / member / role 权限模型。
- OIDC / 企业微信 / 飞书 / LDAP SSO。
- 审计日志。

## 12. 任务卡拆分

详细任务卡已迁移至：[qanything方向任务卡拆分.md](./qanything方向任务卡拆分.md)。

## 13. 总体验收标准

- 用户可以创建知识库并上传一批文件。
- 用户可以看到文档入库状态和失败原因。
- 入库成功后，可以在指定知识库内搜索和问答。
- 问答结果包含可点击引用。
- 引用可以定位到 PDF 页码、文本片段或图片 bbox。
- 删除文档后，搜索和问答不再命中该文档。
- 设置页可以展示当前 provider，并完成至少一种连接测试。
- 前端 6 个页面具备统一 light/dark 风格、统一错误态和统一鉴权处理。
- 知识库、文档资产和入库任务状态持久化在业务 DB 中。
- 第一版不要求 SSO，但核心表预留未来多用户和 SSO 所需字段。
