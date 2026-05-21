# QAnything 方向 Epic 技术方案

依据：`qanything方向产品化补齐_PRD.md`、`qanything方向任务卡拆分.md`、`qanything方向DB表结构设计.md`  
更新时间：2026-05-21  
状态：Draft for Execution

## 1. 总体架构

Phase 4 的核心变化是把当前“检索与问答引擎”补齐为可独立使用的知识库问答产品。

### 1.1 分层边界

| 层 | 职责 | 主要承载 |
|---|---|---|
| React/Next.js | 页面展示、交互、轻量缓存 | 知识库、导入、问答、检索、预览、设置 |
| REST API | 产品接口、鉴权、错误契约 | `/api/v1/kbs`、`/api/v1/search`、`/api/conversations` |
| Application | 业务编排、状态流转、任务聚合 | KnowledgeBase、DocumentAsset、IngestionTask、Conversation |
| Domain | 状态机、领域对象、策略 | 文档状态、任务阶段、去重策略、权限上下文 |
| Infrastructure | DB、ES、Redis、OSS、Provider 适配 | MySQL/PostgreSQL、Elasticsearch、Redis、对象存储 |

### 1.2 存储边界

| 存储 | 用途 |
|---|---|
| DB | 产品主数据、文档状态、任务状态、会话快照、设置、活动记录 |
| ES | `kb_segment`、BM25、向量、bbox、snippet、anchor |
| OSS | 原始文件、预览文件、缩略图 |
| Redis | 短期 token、任务锁、previewUrl 缓存、热点缓存 |

### 1.3 现有能力复用

| 现有模块 | 复用方式 |
|---|---|
| `ingestion` | 复用文本/图片批任务、解析、embedding、索引写入链路，外层增加知识库维度和 DB 任务持久化 |
| `search` | 复用 `UnifiedSearchService`、RRF、rerank、preview service，补 `kbIds`、filters、answer mode |
| `conversation` | 复用会话、消息、answer、citations、resultCards，补 `kbScope`、SSE、DB 快照 |
| `auth` | P0 复用 token 思路，补统一用户上下文 |
| `integration` | 复用 storage、embedding、generation、OCR port，P1/P2 再做 Provider Router |

## 2. E0：协议、DB 与鉴权基线

### 2.1 目标

先固定接口命名、错误结构、DB migration 和用户上下文，避免后续每个模块各自定义协议。

覆盖任务：Q4-00 ~ Q4-04。

### 2.2 技术方案

新增或扩展基础能力：

| 能力 | 方案 |
|---|---|
| DB migration | 建议引入 Flyway 或 Liquibase。若项目暂不引入 migration 工具，先建立 `docs/sql` 草案和启动校验 |
| ID 生成 | 应用层统一生成字符串 ID，建议使用 `kb_`、`doc_`、`task_`、`item_`、`turn_` 前缀 |
| 用户上下文 | P0 固定 `workspaceId=default`、`userId=system`，从 token filter 注入 request context |
| 错误契约 | 统一 `code/message/traceId/details`，业务异常集中映射 |
| API 命名 | P0 固定 `/api/v1/kbs`、`/api/v1/ingestion`、`/api/v1/search`、`/api/conversations` |

建议新增包：

```text
common/application/context
common/interfaces/exception
common/infrastructure/id
common/infrastructure/db
```

### 2.3 数据方案

P0 migration 优先建：

```text
knowledge_base
document_asset
ingestion_task
ingestion_task_item
```

表结构以 `qanything方向DB表结构设计.md` 为准。

### 2.4 接口契约

统一错误响应建议：

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "Document not found.",
  "traceId": "trace_xxx",
  "details": {}
}
```

### 2.5 测试重点

| 测试 | 验收 |
|---|---|
| migration 初始化 | 空库启动成功，核心表存在 |
| token 缺失 | 返回 401，错误结构稳定 |
| traceId | 业务异常和系统异常均返回 traceId |
| 用户上下文 | 业务表写入固定 `workspace_id/created_by` |

### 2.6 风险与取舍

- 不建议 P0 同时接入 SSO，否则会拖慢产品主链路。
- 不建议使用 ES 反推产品主数据，删除、重试、状态统计会失控。
- 如果短期不接 migration 工具，也要保证 SQL 草案和实体字段一一对应。

## 3. E1：知识库与文档资产产品模型

### 3.1 目标

建立用户可感知的知识库和文档资产模型，前端管理的是 `knowledge_base` 和 `document_asset`，不是 ES segment。

覆盖任务：Q4-05 ~ Q4-09。

### 3.2 技术方案

新增领域对象：

| 模型 | 说明 |
|---|---|
| `KnowledgeBase` | 顶层资源，承载名称、状态、统计 |
| `DocumentAsset` | 一份用户上传或导入的资料 |
| `DocumentStatus` | 文档解析和索引状态 |
| `DocumentDedupePolicy` | 去重策略，P0 可先固定为 `SKIP` 或 `VERSIONED` |

建议新增包：

```text
kb/domain/model
kb/domain/repository
kb/application
kb/infrastructure/persistence
kb/interfaces/rest
```

如果不想新增 `kb` 顶层包，也可以放在：

```text
ingestion/domain/model/KnowledgeBase
ingestion/domain/model/DocumentAsset
```

但长期看 `kb` 作为产品域更清晰。

### 3.3 API 设计

| 能力 | 方法 | 路径 |
|---|---|---|
| 创建知识库 | `POST` | `/api/v1/kbs` |
| 知识库列表 | `GET` | `/api/v1/kbs` |
| 知识库详情 | `GET` | `/api/v1/kbs/{kbId}` |
| 更新知识库 | `PATCH` | `/api/v1/kbs/{kbId}` |
| 删除/归档知识库 | `DELETE` | `/api/v1/kbs/{kbId}` |
| 知识库统计 | `GET` | `/api/v1/kbs/{kbId}/stats` |
| 文档列表 | `GET` | `/api/v1/kbs/{kbId}/documents` |
| 文档详情 | `GET` | `/api/v1/kbs/{kbId}/documents/{assetId}` |
| 文档删除 | `DELETE` | `/api/v1/kbs/{kbId}/documents/{assetId}` |

### 3.4 状态流转

`knowledge_base.status`：

```text
ACTIVE -> ARCHIVED
ACTIVE -> DELETING -> deleted_at
```

`document_asset`：

```text
parse_status: PENDING -> RUNNING -> SUCCESS / FAILED
index_status: PENDING -> RUNNING -> SUCCESS / FAILED
```

删除文档时建议先软删除 DB，再异步清理 ES 和 OSS。清理失败时保留可排查状态。

### 3.5 测试重点

| 测试 | 验收 |
|---|---|
| 知识库 CRUD | 可创建、列表、详情、重命名、归档 |
| 文档列表分页 | 支持 limit/cursor 或 page/size |
| 删除文档 | 删除后默认列表不可见 |
| 去重策略 | 同知识库相同 `file_hash` 可识别 |

### 3.6 风险与取舍

- 文档统计字段可以 P0 冗余在 DB，避免每次从 ES 聚合。
- `file_hash` 必须由后端基于内容计算，不直接使用 OSS etag。
- P0 删除可以先软删除，ES/OSS 清理作为异步任务，避免接口阻塞。

## 4. E2：入库任务与导入能力收口

### 4.1 目标

把现有文本和图片两套 batch task 对前端收敛为统一的知识库入库任务接口。

覆盖任务：Q4-10 ~ Q4-14。

### 4.2 技术方案

新增统一入库编排层：

```text
KnowledgeBaseIngestionService
IngestionTaskApplicationService
IngestionCapabilityService
```

内部仍可复用：

```text
TextAssetIngestionService
ImageIngestionService
ImageSegmentIndexWriter
Text parser / embedding / ES writer
```

前端只调用：

```text
/api/v1/kbs/{kbId}/ingestion-tasks
```

后端根据文件类型路由到文本或图片链路，并把任务状态写入 DB。

### 4.3 API 设计

| 能力 | 方法 | 路径 |
|---|---|---|
| 导入能力声明 | `GET` | `/api/v1/ingestion/capabilities` |
| 创建入库任务 | `POST` | `/api/v1/kbs/{kbId}/ingestion-tasks` |
| 任务列表 | `GET` | `/api/v1/kbs/{kbId}/ingestion-tasks` |
| 任务详情 | `GET` | `/api/v1/kbs/{kbId}/ingestion-tasks/{taskId}` |
| 重试失败项 | `POST` | `/api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed` |
| 文档重解析 | `POST` | `/api/v1/kbs/{kbId}/documents/{assetId}/reparse` |
| 文档重向量化 | `POST` | `/api/v1/kbs/{kbId}/documents/{assetId}/reembed` |

### 4.4 任务阶段

统一阶段：

```text
UPLOAD
PARSE
CHUNK
EMBED
INDEX
ASKABLE
```

任务项状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
SKIPPED
```

### 4.5 数据写入策略

1. 创建 `ingestion_task`。
2. 为每个文件或 URL 创建 `ingestion_task_item`。
3. 创建或复用 `document_asset`。
4. 执行解析、chunk、embedding、index。
5. 每个阶段更新 task item。
6. 成功后更新 document parse/index 状态和 segment count。
7. 汇总更新 task success/failure/running count。

### 4.6 测试重点

| 测试 | 验收 |
|---|---|
| capabilities | 返回格式、大小、数量、阶段、去重策略 |
| 混合导入 | 文本和图片都能通过统一任务接口提交 |
| 失败重试 | 单项失败可重试，批量失败可重试 |
| 状态持久化 | 服务重启后任务状态仍可查询 |

### 4.7 风险与取舍

- P0 不要求完全重构现有 text/image service，先用 facade 收口。
- 如果现有任务执行中状态在内存或 Redis，P0 至少要在关键阶段同步落 DB。
- `reparse` 和 `reembed` 建议 P1 做，不阻塞最小闭环。

## 5. E3：检索、问答与预览闭环

### 5.1 目标

让搜索、问答和预览都按知识库范围工作，并形成“问答结果 -> 引用 -> 预览定位”的闭环。

覆盖任务：Q4-15 ~ Q4-22。

### 5.2 Search 技术方案

扩展 `KbSearchQueryDTO`：

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

`UnifiedSearchServiceImpl` 在构建 ES 查询时增加：

```text
terms filter: kb_id in kbIds
terms filter: asset_type in assetTypes
range filter: created_at in dateRange
```

P0 只强制 `kbIds`。filters、cursor、facets 可 P1。

### 5.3 Conversation 技术方案

扩展 `ConversationMessageRequestDTO`：

```text
kbIds
answerMode
preferredModalities
debug
stream
```

`ConversationRetrievalOrchestratorImpl` 将 `kbIds` 传入 `KbSearchQueryDTO`。回答响应补充：

```text
kbScope
answerMode
retrievalStage
```

历史 turn 中保存 `resultCards/citations/retrievalTrace` 快照，避免刷新后重新检索导致引用变化。

### 5.4 Preview 技术方案

复用并增强：

```text
SegmentPreviewService
SegmentPreviewServiceImpl
PreviewAccessCache
SearchObjectStoragePort
```

关键约束：

1. `previewUrl` 只短期签发，不持久化，不写日志。
2. 通过 `segmentId` 查询 ES segment，再还原 `assetId/sourceRef/anchor/surroundingChunks`。
3. bbox 缺失或异常时降级展示 OCR/snippet。
4. P1 支持 neighbors 和 refresh。

### 5.5 API 设计

| 能力 | 方法 | 路径 |
|---|---|---|
| 知识库搜索 | `POST` | `/api/v1/search/kb` |
| 搜索生成答案 P1 | `POST` | `/api/v1/search/kb-answer` |
| 创建消息 | `POST` | `/api/conversations/{sessionId}/messages` |
| 流式消息 P1 | `POST` | `/api/conversations/{sessionId}/messages/stream` |
| segment 预览 | `GET` | `/api/v1/preview/segments/{segmentId}` |
| segment neighbors P1 | `GET` | `/api/v1/preview/segments/{segmentId}/neighbors` |
| preview refresh P1 | `POST` | `/api/v1/preview/segments/{segmentId}/refresh` |

### 5.6 测试重点

| 测试 | 验收 |
|---|---|
| kb filter | 搜索和问答不命中未选知识库 |
| answer citation | 回答引用和 result card 的 segmentId 可打开预览 |
| preview security | 不打印 token 和完整 previewUrl |
| bbox fallback | 无效 bbox 不画框，展示降级信息 |
| stream | SSE 包含 done/error，异常可恢复 |

### 5.7 风险与取舍

- P0 不做搜索页生成答案，避免把问答链路和搜索链路同时复杂化。
- P0 不强制 conversation 迁 DB，但 P1 需要 DB turn 快照支撑长期历史。
- `kbIds` 过滤必须进入 ES 查询层，不能只在返回结果后过滤。

## 6. E4：Ask First 首页与体验聚合

### 6.1 目标

支撑 Ask First 首页首屏和最近活动，不让前端跨多个基础接口拼装。

覆盖任务：Q4-23 ~ Q4-27。

### 6.2 技术方案

新增应用服务：

```text
HomeSummaryService
ActivityQueryService
ActivityEventService
```

P0 的 `home/summary` 可以从现有表派生：

| 模块 | 数据来源 |
|---|---|
| 常用知识库 | `knowledge_base` 按 `updated_at` 或最近任务排序 |
| 最近导入 | `ingestion_task` |
| 最近问题 | P0 返回空数组，P1 从 `conversation_turn` 或 `activity_event` |
| 最近引用 | P0 返回空数组，P1 从 `conversation_citation` 或 `activity_event` |

### 6.3 API 设计

| 能力 | 方法 | 路径 |
|---|---|---|
| 首页聚合 | `GET` | `/api/v1/home/summary` |
| 最近问题 P1 | `GET` | `/api/v1/activity/recent-questions` |
| 最近引用 P1 | `GET` | `/api/v1/activity/recent-citations` |

### 6.4 activity_event 写入点

| 事件 | 写入时机 |
|---|---|
| `QUESTION_ASKED` | 对话消息创建成功 |
| `CITATION_OPENED` | 预览接口成功返回 |
| `DOCUMENT_IMPORTED` | 入库任务创建或完成 |
| `SEARCH_EXECUTED` | 搜索接口成功返回 |

### 6.5 测试重点

| 测试 | 验收 |
|---|---|
| home summary | 单接口返回首屏数据 |
| empty state | 最近问题/引用为空时返回空数组 |
| event write | P1 行为事件能被查询 |
| partial failure | 聚合局部失败不影响核心入口 |

### 6.6 风险与取舍

- P0 不要为首页聚合引入复杂推荐算法。
- P0 可以不做收藏知识库，按最近使用或更新时间替代。
- activity 事件属于体验增强，P1 再做更稳。

## 7. E5：设置、Provider 与外观偏好

### 7.1 目标

让设置页可以查看当前能力和做连接测试，同时为后续 Provider Router 预留配置模型。

覆盖任务：Q4-28 ~ Q4-32。

### 7.2 技术方案

新增应用服务：

```text
SettingsQueryService
SearchSettingService
ProviderSettingService
ProviderConnectionTestService
```

P1 只暴露可安全展示的配置：

| 配置 | 策略 |
|---|---|
| 检索阈值、RRF、rerank window | 可热更新 |
| chunk size / overlap | 新入库生效，旧文档需 reparse |
| generation model | 可先重启生效 |
| embedding model | 需要 reembed |
| OCR provider | P1 可只读，P2 再热切 |
| object storage | P1 可测试，P2 再热切 |

### 7.3 API 设计

| 能力 | 方法 | 路径 |
|---|---|---|
| 能力查询 | `GET` | `/api/v1/settings/capabilities` |
| Provider 查询 | `GET` | `/api/v1/settings/providers` |
| 检索参数查询 | `GET` | `/api/v1/settings/search` |
| 检索参数更新 | `PATCH` | `/api/v1/settings/search` |
| 连接测试 | `POST` | `/api/v1/settings/test-connection` |
| 外观偏好 P1 | `GET/PATCH` | `/api/v1/settings/preferences` |

### 7.4 密钥安全

1. API Key 不明文返回。
2. `provider_setting.config_value` 中不直接保存明文密钥，优先保存 `secret_ref`。
3. 如果短期必须 DB 保存密钥，需要加密存储，并只返回 masked value。
4. 连接测试日志不能打印完整配置。

### 7.5 测试重点

| 测试 | 验收 |
|---|---|
| capabilities | 返回当前启用能力和不可用原因 |
| connection test | 成功和失败都有可读结果 |
| search setting | 可热更新项生效，不可热更新项提示明确 |
| secret masking | 接口响应不出现完整 API Key |

### 7.6 风险与取舍

- P1 不建议支持所有配置热切换。
- Provider Router 会影响启动装配和索引维度，建议 P2 独立做。
- 外观偏好第一版可前端本地保存，跨设备同步不是主线。

## 8. E6：文档格式扩展

### 8.1 目标

在统一入库任务和预览定位底座上补齐办公格式。

覆盖任务：Q4-33 ~ Q4-37。

### 8.2 技术方案

新增 parser port：

```text
DocumentParserPort
DocumentParseResult
ParsedBlock
ParsedTable
ParsedImage
```

各格式 parser 输出统一 block：

| 格式 | 关键保留信息 |
|---|---|
| DOCX | heading path、段落、表格、列表、页内图片 |
| XLSX/CSV | sheet、表头、行号、列名、合并单元格 |
| URL/HTML | title、h1-h6、正文块、代码块、链接上下文 |
| PPTX | slide、标题、正文、备注、图片 OCR |
| ZIP | 文件树、解包项、跳过原因 |

### 8.3 入库链路

```text
DocumentAsset -> Parser -> ParsedBlock -> Chunk -> Embedding -> kb_segment -> Preview anchor
```

P1 优先：

```text
DOCX
XLSX / CSV
URL
```

P2：

```text
PPTX
ZIP
```

### 8.4 测试重点

| 测试 | 验收 |
|---|---|
| DOCX | heading 和段落可检索，表格不丢 |
| XLSX/CSV | sheet、行列定位可回显 |
| URL | 抓取失败原因可见 |
| PPTX | slide 页码可定位 |
| ZIP | 不支持格式可跳过，不影响其他文件 |

### 8.5 风险与取舍

- 不追求 P1 完美版式还原，优先保证可检索、可问答、可定位。
- 表格 chunk 要避免整表塞入单 chunk，可按标题、表头和行组切分。
- URL 抓取需要防 SSRF，P1 至少限制内网地址和协议。

## 9. E7：账号、权限与企业集成

### 9.1 目标

从 P0 单用户演进到本地账号，再到 Workspace、权限、SSO 和审计。

覆盖任务：Q4-38 ~ Q4-42。

### 9.2 技术方案

演进路径：

```text
P0: fixed system user
P1: user_account + local login
P2: workspace + workspace_member + role + SSO
```

权限判断统一放在应用服务入口，不散落到 controller：

```text
PermissionService
WorkspaceContext
ResourceOwnershipChecker
```

### 9.3 数据模型

P1：

```text
user_account
```

P2：

```text
workspace
workspace_member
audit_log
provider_config_version
```

### 9.4 权限矩阵

| 角色 | 查看 | 搜索/问答 | 导入 | 删除文档 | 设置 | 成员管理 |
|---|---|---|---|---|---|---|
| OWNER | 是 | 是 | 是 | 是 | 是 | 是 |
| ADMIN | 是 | 是 | 是 | 是 | 是 | 是 |
| EDITOR | 是 | 是 | 是 | 是 | 否 | 否 |
| VIEWER | 是 | 是 | 否 | 否 | 否 | 否 |

### 9.5 SSO 方案

P2 优先 OIDC：

1. 外部身份登录。
2. 根据 issuer + subject 映射 `user_account.external_subject`。
3. 首次登录创建或绑定用户。
4. Workspace 成员关系由管理员邀请或域名规则生成。

企业微信、飞书、LDAP 可作为后续 provider。

### 9.6 审计方案

审计事件：

```text
LOGIN
DOCUMENT_IMPORTED
DOCUMENT_DELETED
KB_CREATED
SETTING_UPDATED
MEMBER_UPDATED
SSO_LOGIN_FAILED
```

### 9.7 测试重点

| 测试 | 验收 |
|---|---|
| 本地登录 | 密码哈希，不明文存储 |
| 权限矩阵 | VIEWER 无法删除，EDITOR 可导入 |
| SSO 映射 | 外部身份可绑定本地用户 |
| 审计 | 可按用户、资源、时间查询 |

### 9.8 风险与取舍

- SSO 不进 P0。
- P1 本地账号只做基础隔离，不做完整企业权限。
- 审计日志和 activity_event 分开，前者偏合规，后者偏体验。

## 10. E8：验收、文档与联调

### 10.1 目标

形成可接口测试、可前后端联调、可回归的交付闭环。

覆盖任务：Q4-43 ~ Q4-47。

### 10.2 技术方案

输出三类验收资产：

| 资产 | 内容 |
|---|---|
| REST API 验收文档 | 请求示例、响应示例、错误码、验收点 |
| 前端联调清单 | baseUrl、token、测试知识库、测试文件、页面状态 |
| 端到端验收用例 | 10-20 条 query、预期引用、预览定位记录 |

### 10.3 推荐验收顺序

```text
1. 获取 token
2. 创建知识库
3. 查询导入能力
4. 上传/导入文档
5. 查询任务状态
6. 查询文档列表
7. 关键词检索
8. 创建会话并问答
9. 点击引用预览
10. 查询首页聚合
```

### 10.4 指标

| 指标 | 目标 |
|---|---|
| 对话页面首屏 | P95 < 2.5s |
| 预览接口 | P95 < 800ms |
| 文档列表 | 支持分页 |
| 搜索结果 | 支持分页或 cursor |
| 引用预览 | PDF/TXT/MD/IMAGE 均有降级策略 |

### 10.5 测试重点

| 测试 | 验收 |
|---|---|
| P0 主链路 | 创建知识库 -> 导入 -> 搜索/问答 -> 预览 |
| 负向用例 | token 缺失、文档不存在、任务失败、preview 过期 |
| 数据隔离 | 不同 `kbIds` 结果不串 |
| 文档同步 | PRD、任务卡、DB 表、接口文档一致 |

### 10.6 风险与取舍

- 不要等前端全部完成才做接口验收，P0 后端应先用 HTTP 文档跑通。
- 验收样例要固定，避免每次模型生成差异导致无法判断回归。
- 性能验收先覆盖接口级 P95，不必一开始做完整压测平台。

## 11. 实施顺序建议

| 顺序 | Epic | 原因 |
|---:|---|---|
| 1 | E0 | DB、鉴权、错误契约是所有模块的底座 |
| 2 | E1 | 先建立知识库和文档资产，否则入库、搜索、问答都没有产品对象 |
| 3 | E2 | 统一导入任务，为文档管理页提供状态 |
| 4 | E3 | 完成搜索、问答、预览核心闭环 |
| 5 | E4 | Ask 首页聚合可以在 E1/E2/E3 基础上快速实现 |
| 6 | E8 | P0 接口验收和联调应穿插进行 |
| 7 | E5/E6/E7 | P1/P2 体验、格式和企业能力逐步补齐 |

## 12. P0 最小技术闭环

P0 完成后必须满足：

```text
KnowledgeBase DB model
DocumentAsset DB model
IngestionTask DB model
kbIds search filter
kbIds conversation filter
segment preview
home summary
admin token
unified error schema
```

对应最小链路：

```text
POST /api/v1/kbs
GET  /api/v1/ingestion/capabilities
POST /api/v1/kbs/{kbId}/ingestion-tasks
GET  /api/v1/kbs/{kbId}/documents
POST /api/v1/search/kb
POST /api/conversations/{sessionId}/messages
GET  /api/v1/preview/segments/{segmentId}
GET  /api/v1/home/summary
```

## 13. Epic 实现细化

### 13.1 E0 实现细化

#### 后端落点

| 模块 | 建议类/接口 | 说明 |
|---|---|---|
| ID 生成 | `IdGenerator` | 统一生成 `kb_`、`doc_`、`task_`、`item_`、`turn_` 前缀 ID |
| 用户上下文 | `RequestUserContext`、`UserContextHolder` | P0 固定 `workspaceId=default`、`userId=system` |
| 鉴权 | `AuthTokenFilter` 或扩展现有 `@RequireAuth` | 统一读取 token 并注入上下文 |
| 错误处理 | `GlobalApiExceptionHandler` | 将业务异常、校验异常、系统异常映射为统一结构 |
| DB migration | `db/migration/V4_*.sql` | P0 建核心表，P1/P2 追加表 |

#### 错误码建议

| code | HTTP | 场景 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | token 缺失或无效 |
| `FORBIDDEN` | 403 | 后续权限不足 |
| `VALIDATION_ERROR` | 400 | 请求参数非法 |
| `KNOWLEDGE_BASE_NOT_FOUND` | 404 | 知识库不存在 |
| `DOCUMENT_NOT_FOUND` | 404 | 文档不存在 |
| `INGESTION_TASK_NOT_FOUND` | 404 | 入库任务不存在 |
| `SEGMENT_NOT_FOUND` | 404 | 预览 segment 不存在 |
| `TASK_STATE_CONFLICT` | 409 | 当前任务状态不允许操作 |
| `PROVIDER_UNAVAILABLE` | 503 | 模型、OCR、对象存储连接不可用 |
| `INTERNAL_ERROR` | 500 | 未预期异常 |

### 13.2 E1 实现细化

#### 聚合设计

```text
KnowledgeBase aggregate
  ├─ id / workspaceId / name / status
  ├─ documentCount / segmentCount
  └─ stats refresh policy

DocumentAsset aggregate
  ├─ id / kbId / workspaceId
  ├─ file metadata / source metadata
  ├─ parseStatus / indexStatus
  └─ objectKey / previewObjectKey / thumbnailKey
```

#### Repository 边界

| Repository | 方法 |
|---|---|
| `KnowledgeBaseRepository` | `save`、`findById`、`listByWorkspace`、`archive`、`updateStats` |
| `DocumentAssetRepository` | `save`、`findById`、`listByKb`、`findByHash`、`markDeleted`、`updateStatus` |

#### 统计策略

- P0：`document_count` 和 `segment_count` 允许冗余在 DB，由入库成功后更新。
- P1：补定时校验任务，发现 DB 与 ES 统计不一致时记录告警。
- 删除文档时先软删除 `document_asset`，再异步清理 ES segment 和 OSS 对象。

### 13.3 E2 实现细化

#### 统一入库 facade

```text
KbIngestionApplicationService
  ├─ validate capabilities
  ├─ create ingestion_task
  ├─ create ingestion_task_item
  ├─ create or reuse document_asset
  ├─ route to text/image/url processor
  └─ update task summary
```

#### Processor 边界

| Processor | 输入 | 输出 |
|---|---|---|
| `TextAssetProcessor` | `DocumentAsset` + objectKey | parsed blocks / segments |
| `ImageAssetProcessor` | `DocumentAsset` + objectKey | OCR/caption/bbox segments |
| `UrlAssetProcessor` P1 | sourceUrl | HTML parsed blocks |
| `ReparseProcessor` P1 | assetId | 新 parse 结果 |
| `ReembedProcessor` P1 | assetId | 新 embedding / ES update |

#### 状态一致性

单个文件处理建议采用“任务项状态优先、文档状态汇总”的策略：

```text
task_item.stage = PARSE / CHUNK / EMBED / INDEX / ASKABLE
task_item.status = RUNNING / SUCCESS / FAILED
document_asset.parse_status = task item parse result
document_asset.index_status = task item index result
ingestion_task.status = items summary
```

如果 ES 写入成功但 DB 更新失败，需要通过补偿任务或重试机制修正状态。

### 13.4 E3 实现细化

#### 搜索链路

```text
KbSearchApiController
  -> UnifiedSearchService
    -> query validation
    -> query embedding
    -> ES lexical/vector retrieval with kbIds filter
    -> RRF fusion
    -> rerank
    -> aggregate by asset if needed
    -> KbSearchResultDTO
```

#### 对话链路

```text
ConversationApiController
  -> ConversationService
    -> create turn
    -> rewrite query
    -> ConversationRetrievalOrchestrator
      -> UnifiedSearchService(kbIds)
    -> AnswerGenerationService
    -> ResultCardMapper
    -> save turn snapshot
```

#### 预览链路

```text
SegmentPreviewApiController
  -> SegmentPreviewService
    -> find segment from ES
    -> build preview URL through SearchObjectStoragePort
    -> cache signed URL by segmentId + token hash
    -> return previewType / anchor / surroundingChunks
```

#### ES 查询约束

`kbIds` 必须进入 ES bool filter：

```json
{
  "bool": {
    "filter": [
      { "terms": { "kbId": ["kb_xxx"] } }
    ]
  }
}
```

不能只在 Java 层过滤返回结果，否则会影响召回、RRF、rerank 和分页准确性。

### 13.5 E4 实现细化

#### 首页聚合数据来源

| 字段 | P0 来源 | P1 来源 |
|---|---|---|
| `favoriteKbs` | `knowledge_base.updated_at` 排序 | 用户收藏表或最近使用事件 |
| `recentQuestions` | 空数组 | `conversation_turn` 或 `activity_event` |
| `recentCitations` | 空数组 | `conversation_citation` 或 `activity_event` |
| `recentIngestionTasks` | `ingestion_task` | 同 P0 |
| `helpLinks` | 静态配置 | `app_setting` |

#### 聚合容错

首页聚合不应因为某一块失败导致整个接口失败。建议响应里增加 `warnings`：

```json
{
  "favoriteKbs": [],
  "recentQuestions": [],
  "recentCitations": [],
  "recentIngestionTasks": [],
  "warnings": []
}
```

### 13.6 E5 实现细化

#### 设置分层

| 类型 | 存储 | 是否热更新 |
|---|---|---|
| UI 偏好 | localStorage 或 `app_setting` | 是 |
| 检索参数 | `app_setting` | 部分是 |
| provider 元信息 | 配置文件 + `provider_setting` | P1 只读，P2 热切 |
| secret | secret manager 或加密 DB | 否 |

#### 连接测试策略

`test-connection` 不应修改当前配置，只验证传入 provider 或当前 provider 是否可用。

### 13.7 E6 实现细化

#### Parser 输出统一模型

```text
ParsedDocument
  ├─ title
  ├─ blocks[]
  └─ attachments[]

ParsedBlock
  ├─ blockId
  ├─ blockType: HEADING / PARAGRAPH / TABLE / IMAGE / SLIDE
  ├─ text
  ├─ headingPath
  ├─ pageNo / sheetName / rowRange / columnRange / slideNo
  └─ bbox
```

所有格式最终都转为 segment 写入 ES，前端预览只依赖统一 anchor。

### 13.8 E7 实现细化

#### 权限检查位置

权限判断统一放在 application service，不放在 Controller 里：

```text
Controller -> ApplicationService -> PermissionService -> Domain operation
```

P0 `PermissionService` 固定允许系统用户，P2 替换为 Workspace role 规则。

### 13.9 E8 实现细化

#### 验收资产

| 文件 | 内容 |
|---|---|
| REST API 验收文档 | P0 接口请求、响应、错误码、验收点 |
| HTTP collection | 可直接执行的接口样例 |
| E2E checklist | 从创建知识库到预览证据的完整步骤 |
| 回归样例 | 10-20 条固定问题、预期引用和预览位置 |

## 14. REST 接口定义

### 14.1 通用约定

#### Header

```http
X-Access-Token: <token>
Content-Type: application/json
```

P0 固定 token / 单用户；P1/P2 可替换为登录态或 SSO token。

#### 成功响应

```json
{
  "success": true,
  "data": {},
  "traceId": "trace_xxx"
}
```

如沿用当前项目 `Result<T>` 包装，字段可保持当前实现，但必须保证错误结构稳定。

#### 失败响应

```json
{
  "success": false,
  "code": "VALIDATION_ERROR",
  "message": "Invalid request.",
  "traceId": "trace_xxx",
  "details": {
    "field": "name"
  }
}
```

#### 分页参数

P0 建议统一使用：

```text
limit
cursor
```

响应：

```json
{
  "items": [],
  "nextCursor": null
}
```

### 14.2 KnowledgeBase 接口

#### 创建知识库

```http
POST /api/v1/kbs
```

请求：

```json
{
  "name": "产品知识库",
  "description": "产品文档、FAQ、方案材料"
}
```

响应：

```json
{
  "kbId": "kb_01",
  "name": "产品知识库",
  "description": "产品文档、FAQ、方案材料",
  "status": "ACTIVE",
  "documentCount": 0,
  "segmentCount": 0,
  "createdAt": 1779350400000,
  "updatedAt": 1779350400000
}
```

校验：

| 字段 | 规则 |
|---|---|
| `name` | 必填，1-128 字符 |
| `description` | 可选，最长 2000 字符 |

#### 知识库列表

```http
GET /api/v1/kbs?status=ACTIVE&limit=20&cursor=
```

响应：

```json
{
  "items": [
    {
      "kbId": "kb_01",
      "name": "产品知识库",
      "description": "产品文档、FAQ、方案材料",
      "status": "ACTIVE",
      "documentCount": 12,
      "segmentCount": 394,
      "lastIngestedAt": 1779350400000,
      "updatedAt": 1779350400000
    }
  ],
  "nextCursor": null
}
```

#### 知识库详情

```http
GET /api/v1/kbs/{kbId}
```

响应同列表 item，补充创建人、更新时间等字段。

#### 更新知识库

```http
PATCH /api/v1/kbs/{kbId}
```

请求：

```json
{
  "name": "产品资料库",
  "description": "更新后的描述"
}
```

响应：更新后的 KnowledgeBase。

#### 删除或归档知识库

```http
DELETE /api/v1/kbs/{kbId}
```

请求可选：

```json
{
  "mode": "ARCHIVE"
}
```

`mode`：

```text
ARCHIVE
DELETE
```

P0 默认建议归档，硬删除或级联清理放到异步任务。

#### 知识库统计

```http
GET /api/v1/kbs/{kbId}/stats
```

响应：

```json
{
  "kbId": "kb_01",
  "documentCount": 12,
  "segmentCount": 394,
  "runningTaskCount": 1,
  "failedTaskCount": 0,
  "lastIngestionStatus": "RUNNING",
  "lastIngestedAt": 1779350400000
}
```

### 14.3 DocumentAsset 接口

#### 文档列表

```http
GET /api/v1/kbs/{kbId}/documents?status=ALL&assetType=PDF&limit=20&cursor=
```

响应：

```json
{
  "items": [
    {
      "assetId": "doc_01",
      "kbId": "kb_01",
      "fileName": "产品手册.pdf",
      "title": "产品手册",
      "fileType": "PDF",
      "mimeType": "application/pdf",
      "sizeBytes": 204800,
      "fileHash": "sha256_xxx",
      "parseStatus": "SUCCESS",
      "indexStatus": "SUCCESS",
      "segmentCount": 52,
      "errorCode": null,
      "errorMessage": null,
      "createdAt": 1779350400000,
      "updatedAt": 1779350400000
    }
  ],
  "nextCursor": null
}
```

#### 文档详情

```http
GET /api/v1/kbs/{kbId}/documents/{assetId}
```

响应：

```json
{
  "assetId": "doc_01",
  "kbId": "kb_01",
  "fileName": "产品手册.pdf",
  "title": "产品手册",
  "fileType": "PDF",
  "mimeType": "application/pdf",
  "sizeBytes": 204800,
  "fileHash": "sha256_xxx",
  "sourceUrl": null,
  "parseStatus": "SUCCESS",
  "indexStatus": "SUCCESS",
  "segmentCount": 52,
  "embeddingProfile": "default",
  "latestTaskId": "task_01",
  "createdAt": 1779350400000,
  "updatedAt": 1779350400000
}
```

#### 删除文档 P1

```http
DELETE /api/v1/kbs/{kbId}/documents/{assetId}
```

响应：

```json
{
  "assetId": "doc_01",
  "deleteStatus": "DELETING"
}
```

删除语义：

1. DB 先软删除。
2. ES segment 异步清理。
3. OSS 原文件/预览文件/缩略图按策略清理。
4. 搜索和问答必须立即过滤 deleted 文档。

### 14.4 Ingestion 接口

#### 导入能力声明

```http
GET /api/v1/ingestion/capabilities
```

响应：

```json
{
  "supportedFormats": [
    {
      "fileType": "PDF",
      "extensions": ["pdf"],
      "mimeTypes": ["application/pdf"],
      "enabled": true,
      "priority": "P0"
    },
    {
      "fileType": "IMAGE",
      "extensions": ["png", "jpg", "jpeg", "webp"],
      "mimeTypes": ["image/png", "image/jpeg", "image/webp"],
      "enabled": true,
      "priority": "P0"
    }
  ],
  "maxFileSizeBytes": 209715200,
  "maxFilesPerBatch": 50,
  "dedupeStrategies": ["SKIP", "OVERWRITE", "VERSIONED"],
  "defaultDedupeStrategy": "SKIP",
  "ingestionStages": ["UPLOAD", "PARSE", "CHUNK", "EMBED", "INDEX", "ASKABLE"]
}
```

#### 创建知识库入库任务

```http
POST /api/v1/kbs/{kbId}/ingestion-tasks
```

请求：

```json
{
  "sourceType": "UPLOAD",
  "dedupeStrategy": "SKIP",
  "items": [
    {
      "fileName": "产品手册.pdf",
      "title": "产品手册",
      "fileType": "PDF",
      "mimeType": "application/pdf",
      "sizeBytes": 204800,
      "objectKey": "uploads/kb_01/product.pdf",
      "fileHash": "sha256_xxx"
    }
  ]
}
```

说明：

- `fileHash` 最终以后端计算为准。接口测试可传固定值；正式上传链路应由后端基于文件内容计算或校验。
- URL 导入时使用 `sourceType=URL`，item 传 `sourceUrl`。

响应：

```json
{
  "taskId": "task_01",
  "kbId": "kb_01",
  "sourceType": "UPLOAD",
  "status": "PENDING",
  "totalCount": 1,
  "successCount": 0,
  "failureCount": 0,
  "runningCount": 0,
  "items": [
    {
      "itemId": "item_01",
      "assetId": "doc_01",
      "fileName": "产品手册.pdf",
      "stage": "UPLOAD",
      "status": "PENDING",
      "progress": 0,
      "dedupeResult": "NEW"
    }
  ],
  "createdAt": 1779350400000
}
```

#### 入库任务列表

```http
GET /api/v1/kbs/{kbId}/ingestion-tasks?status=RUNNING&limit=20&cursor=
```

响应：

```json
{
  "items": [
    {
      "taskId": "task_01",
      "kbId": "kb_01",
      "sourceType": "UPLOAD",
      "status": "RUNNING",
      "totalCount": 3,
      "successCount": 1,
      "failureCount": 0,
      "runningCount": 2,
      "createdAt": 1779350400000,
      "updatedAt": 1779350500000
    }
  ],
  "nextCursor": null
}
```

#### 入库任务详情

```http
GET /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}
```

响应：

```json
{
  "taskId": "task_01",
  "kbId": "kb_01",
  "sourceType": "UPLOAD",
  "status": "PARTIAL_SUCCESS",
  "totalCount": 2,
  "successCount": 1,
  "failureCount": 1,
  "runningCount": 0,
  "items": [
    {
      "itemId": "item_01",
      "assetId": "doc_01",
      "fileName": "产品手册.pdf",
      "stage": "ASKABLE",
      "status": "SUCCESS",
      "progress": 100,
      "dedupeResult": "NEW",
      "errorCode": null,
      "errorMessage": null
    },
    {
      "itemId": "item_02",
      "assetId": "doc_02",
      "fileName": "错误文件.pdf",
      "stage": "PARSE",
      "status": "FAILED",
      "progress": 20,
      "errorCode": "PARSE_FAILED",
      "errorMessage": "Failed to parse PDF."
    }
  ]
}
```

#### 重试失败项

```http
POST /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed
```

响应：返回更新后的任务详情。

#### 文档重解析 P1

```http
POST /api/v1/kbs/{kbId}/documents/{assetId}/reparse
```

响应：

```json
{
  "taskId": "task_reparse_01",
  "assetId": "doc_01",
  "status": "PENDING"
}
```

#### 文档重向量化 P1

```http
POST /api/v1/kbs/{kbId}/documents/{assetId}/reembed
```

响应同 reparse。

### 14.5 Search 接口

#### 知识库搜索

```http
POST /api/v1/search/kb
```

请求：

```json
{
  "query": "付款期限是什么",
  "kbIds": ["kb_01"],
  "topK": 20,
  "limit": 10,
  "strategy": "HYBRID",
  "assetTypes": ["PDF", "DOCX", "IMAGE"],
  "hitTypes": ["TEXT", "OCR", "CAPTION"],
  "dateRange": {
    "from": 1776768000000,
    "to": 1779350400000
  },
  "cursor": null,
  "sort": "RELEVANCE",
  "withAnswer": false
}
```

P0 必填：

```text
query
kbIds
```

P1 字段：

```text
assetTypes
hitTypes
dateRange
cursor
sort
facets
withAnswer
```

响应：

```json
{
  "items": [
    {
      "segmentId": "seg_01",
      "assetId": "doc_01",
      "kbId": "kb_01",
      "assetType": "PDF",
      "segmentType": "TEXT_CHUNK",
      "fileName": "合同.pdf",
      "title": "合同",
      "snippet": "甲方应在验收合格后30日内完成付款。",
      "pageNo": 3,
      "score": 0.92,
      "anchor": {
        "pageNo": 3,
        "chunkOrder": 12,
        "bbox": null
      },
      "thumbnail": null,
      "ocrSummary": null,
      "explain": {
        "matchedBy": ["BM25", "VECTOR", "RERANK"],
        "keywordScore": 0.71,
        "vectorScore": 0.83,
        "rerankScore": 0.92
      }
    }
  ],
  "total": 42,
  "nextCursor": "cursor_xxx",
  "facets": {
    "assetTypes": [
      { "value": "PDF", "count": 12 }
    ],
    "hitTypes": [
      { "value": "TEXT", "count": 30 }
    ]
  }
}
```

#### 搜索生成答案 P1

```http
POST /api/v1/search/kb-answer
```

请求：

```json
{
  "query": "付款期限是什么",
  "kbIds": ["kb_01"],
  "answerMode": "STRICT",
  "topK": 20,
  "limit": 10
}
```

响应：

```json
{
  "answer": "合同约定甲方应在验收合格后30日内完成付款。[1]",
  "citations": [
    {
      "citationIndex": 1,
      "segmentId": "seg_01",
      "assetId": "doc_01",
      "fileName": "合同.pdf",
      "pageNo": 3,
      "snippet": "甲方应在验收合格后30日内完成付款。"
    }
  ],
  "results": [],
  "answerTrace": {
    "mode": "STRICT",
    "grounded": true
  }
}
```

生成失败时：

```json
{
  "answer": null,
  "citations": [],
  "results": [],
  "answerTrace": {
    "mode": "STRICT",
    "grounded": false,
    "fallbackReason": "NO_ENOUGH_EVIDENCE"
  }
}
```

### 14.6 Conversation 接口

#### 创建会话

```http
POST /api/conversations
```

请求：

```json
{
  "title": "产品问答",
  "kbIds": ["kb_01"]
}
```

响应：

```json
{
  "sessionId": "sess_01",
  "title": "产品问答",
  "status": "ACTIVE",
  "kbScope": ["kb_01"],
  "createdAt": 1779350400000,
  "updatedAt": 1779350400000
}
```

#### 会话列表

```http
GET /api/conversations?limit=20&cursor=
```

响应：

```json
{
  "items": [
    {
      "sessionId": "sess_01",
      "title": "产品问答",
      "status": "ACTIVE",
      "lastMessagePreview": "付款期限是什么？",
      "kbScope": ["kb_01"],
      "updatedAt": 1779350400000
    }
  ],
  "nextCursor": null
}
```

#### 发送消息

```http
POST /api/conversations/{sessionId}/messages
```

请求：

```json
{
  "query": "付款期限是什么？",
  "kbIds": ["kb_01"],
  "answerMode": "STRICT",
  "preferredModalities": ["TEXT", "IMAGE"],
  "debug": false,
  "stream": false
}
```

响应：

```json
{
  "sessionId": "sess_01",
  "turnId": "turn_01",
  "rewrittenQuery": "合同 付款期限 验收后 付款",
  "answer": "合同约定付款应在验收合格后30日内完成。[1]",
  "kbScope": ["kb_01"],
  "answerMode": "STRICT",
  "citations": [
    {
      "citationIndex": 1,
      "segmentId": "seg_01",
      "assetId": "doc_01",
      "fileName": "合同.pdf",
      "pageNo": 3,
      "snippet": "甲方应在验收合格后30日内完成付款。"
    }
  ],
  "resultCards": [
    {
      "assetId": "doc_01",
      "assetType": "PDF",
      "fileName": "合同.pdf",
      "title": "合同",
      "score": 0.92,
      "hitCount": 3,
      "primaryHit": {
        "segmentId": "seg_01",
        "snippet": "甲方应在验收合格后30日内完成付款。",
        "score": 0.92,
        "pageNo": 3,
        "anchor": {
          "pageNo": 3,
          "chunkOrder": 12
        },
        "hitType": "TEXT"
      },
      "additionalHits": []
    }
  ],
  "retrievalTrace": null,
  "suggestedQuestions": [
    "验收合格的定义是什么？"
  ],
  "createdAt": 1779350400000
}
```

#### 流式发送 P1

```http
POST /api/conversations/{sessionId}/messages/stream
Accept: text/event-stream
```

事件：

```text
event: trace
data: {"stage":"retrieval","message":"retrieved 20 candidates"}

event: delta
data: {"text":"合同约定付款应"}

event: citations
data: [{"citationIndex":1,"segmentId":"seg_01"}]

event: done
data: {"turnId":"turn_01"}

event: error
data: {"code":"PROVIDER_UNAVAILABLE","message":"Generation provider unavailable."}
```

#### 消息列表

```http
GET /api/conversations/{sessionId}/messages?limit=50&beforeTurnId=
```

响应：

```json
{
  "turns": [
    {
      "turnId": "turn_01",
      "query": "付款期限是什么？",
      "answer": "合同约定付款应在验收合格后30日内完成。[1]",
      "citations": [],
      "resultCards": [],
      "createdAt": 1779350400000
    }
  ],
  "nextCursor": null
}
```

### 14.7 Preview 接口

#### Segment 预览

```http
GET /api/v1/preview/segments/{segmentId}
```

响应：

```json
{
  "segmentId": "seg_01",
  "assetId": "doc_01",
  "kbId": "kb_01",
  "assetType": "PDF",
  "segmentType": "TEXT_CHUNK",
  "fileName": "合同.pdf",
  "previewType": "PDF",
  "previewUrl": "https://signed-url",
  "expiresAt": 1779350700000,
  "sourceRef": "oss://bucket/contracts.pdf",
  "thumbnail": null,
  "title": "合同",
  "snippet": "甲方应在验收合格后30日内完成付款。",
  "ocrSummary": null,
  "anchor": {
    "pageNo": 3,
    "chunkOrder": 12,
    "bbox": null,
    "imageWidth": null,
    "imageHeight": null
  },
  "surroundingChunks": [
    {
      "segmentId": "seg_00",
      "content": "前一段内容",
      "order": 11
    },
    {
      "segmentId": "seg_01",
      "content": "甲方应在验收合格后30日内完成付款。",
      "order": 12
    }
  ],
  "citationContext": {
    "sourceQuestion": "付款期限是什么？",
    "answerClaim": "付款应在验收合格后30日内完成",
    "citationIndex": 1,
    "citationReason": "该片段直接包含付款触发条件和期限。"
  }
}
```

P0 可不返回 `citationContext`，P1 增强。

#### Neighbors P1

```http
GET /api/v1/preview/segments/{segmentId}/neighbors?before=3&after=3
```

响应：

```json
{
  "segmentId": "seg_01",
  "items": [
    {
      "segmentId": "seg_00",
      "content": "前一段内容",
      "order": 11
    }
  ]
}
```

#### Refresh P1

```http
POST /api/v1/preview/segments/{segmentId}/refresh
```

响应：同 segment 预览，重新签发 `previewUrl`。

### 14.8 Home 与 Activity 接口

#### 首页聚合

```http
GET /api/v1/home/summary
```

响应：

```json
{
  "favoriteKbs": [
    {
      "kbId": "kb_01",
      "name": "产品知识库",
      "documentCount": 12,
      "segmentCount": 394,
      "updatedAt": 1779350400000
    }
  ],
  "recentQuestions": [
    {
      "turnId": "turn_01",
      "sessionId": "sess_01",
      "question": "付款期限是什么？",
      "createdAt": 1779350400000
    }
  ],
  "recentCitations": [
    {
      "segmentId": "seg_01",
      "assetId": "doc_01",
      "fileName": "合同.pdf",
      "snippet": "甲方应在验收合格后30日内完成付款。",
      "openedAt": 1779350400000
    }
  ],
  "recentIngestionTasks": [
    {
      "taskId": "task_01",
      "kbId": "kb_01",
      "status": "RUNNING",
      "totalCount": 3,
      "successCount": 1,
      "failureCount": 0
    }
  ],
  "helpLinks": [],
  "warnings": []
}
```

#### 最近问题 P1

```http
GET /api/v1/activity/recent-questions?limit=10&cursor=
```

响应：

```json
{
  "items": [
    {
      "turnId": "turn_01",
      "sessionId": "sess_01",
      "question": "付款期限是什么？",
      "kbScope": ["kb_01"],
      "createdAt": 1779350400000
    }
  ],
  "nextCursor": null
}
```

#### 最近引用 P1

```http
GET /api/v1/activity/recent-citations?limit=10&cursor=
```

响应：

```json
{
  "items": [
    {
      "segmentId": "seg_01",
      "assetId": "doc_01",
      "kbId": "kb_01",
      "fileName": "合同.pdf",
      "title": "合同",
      "snippet": "甲方应在验收合格后30日内完成付款。",
      "citationReason": "该片段直接包含付款触发条件和期限。",
      "openedAt": 1779350400000
    }
  ],
  "nextCursor": null
}
```

### 14.9 Settings 接口

#### 能力查询

```http
GET /api/v1/settings/capabilities
```

响应：

```json
{
  "generation": {
    "enabled": true,
    "provider": "aliyun",
    "model": "qwen-plus"
  },
  "embedding": {
    "enabled": true,
    "provider": "aliyun",
    "model": "text-embedding-v3",
    "dimension": 1024
  },
  "ocr": {
    "enabled": true,
    "provider": "aliyun"
  },
  "objectStorage": {
    "enabled": true,
    "provider": "oss"
  },
  "webSearch": {
    "enabled": false,
    "reason": "Provider not configured."
  }
}
```

#### Provider 查询

```http
GET /api/v1/settings/providers
```

响应：

```json
{
  "providers": [
    {
      "providerType": "GENERATION",
      "providerName": "aliyun",
      "enabled": true,
      "maskedApiKey": "sk-***",
      "hotSwitchable": false
    }
  ]
}
```

#### 检索参数查询

```http
GET /api/v1/settings/search
```

响应：

```json
{
  "topK": 20,
  "rerankWindow": 50,
  "rrfK": 60,
  "minScore": 0.1,
  "hotUpdateSupported": true,
  "requiresReindexFields": ["embeddingModel", "embeddingDimension"]
}
```

#### 检索参数更新 P1

```http
PATCH /api/v1/settings/search
```

请求：

```json
{
  "topK": 20,
  "rerankWindow": 50,
  "minScore": 0.12
}
```

响应：

```json
{
  "updated": true,
  "effectiveImmediately": true,
  "warnings": []
}
```

#### 连接测试 P1

```http
POST /api/v1/settings/test-connection
```

请求：

```json
{
  "providerType": "GENERATION",
  "providerName": "aliyun"
}
```

响应：

```json
{
  "success": true,
  "latencyMs": 320,
  "message": "Connection test succeeded."
}
```

失败：

```json
{
  "success": false,
  "code": "PROVIDER_UNAVAILABLE",
  "message": "Generation provider timeout.",
  "latencyMs": 3000
}
```

#### 外观偏好 P1

```http
GET /api/v1/settings/preferences
PATCH /api/v1/settings/preferences
```

请求：

```json
{
  "theme": "SYSTEM"
}
```

响应：

```json
{
  "theme": "SYSTEM"
}
```

### 14.10 Account / Workspace 接口 P1/P2

P1 本地账号：

```http
POST /api/v1/account/login
POST /api/v1/account/logout
GET  /api/v1/account/me
```

P2 Workspace：

```http
GET  /api/v1/workspaces
POST /api/v1/workspaces
GET  /api/v1/workspaces/{workspaceId}/members
POST /api/v1/workspaces/{workspaceId}/members
PATCH /api/v1/workspaces/{workspaceId}/members/{userId}
DELETE /api/v1/workspaces/{workspaceId}/members/{userId}
```

P2 SSO：

```http
GET  /api/v1/sso/oidc/login
GET  /api/v1/sso/oidc/callback
POST /api/v1/sso/logout
```

P2 审计：

```http
GET /api/v1/audit-logs?action=&resourceType=&userId=&from=&to=&limit=&cursor=
```

这些接口不进入 P0，实现前需先完成 Workspace 和权限模型。
