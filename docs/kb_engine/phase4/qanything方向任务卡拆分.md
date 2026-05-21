# QAnything 方向产品化任务卡

依据：`qanything方向产品化补齐_PRD.md`、`qanything方向能力补齐方案.md`、`qanything方向DB表结构设计.md`、`qanything方向Epic技术方案.md`  
更新时间：2026-05-21  
状态：Draft for Execution

## 1. 阶段目标

把当前“检索与问答引擎”升级为 Ask First 风格的知识库问答产品闭环：

1. 用户可以创建知识库，并把资料导入指定知识库。
2. 用户可以查看文档入库、解析、向量化和索引状态。
3. 用户可以在指定知识库范围内进行对话问答和关键词检索。
4. 用户可以点击引用进入预览页，并定位到 PDF/TXT/MD/IMAGE 原文证据。
5. 首页、最近问题、最近引用等 Ask First 体验可以基于后端聚合接口稳定渲染。
6. DB、鉴权、错误态和配置能力为后续多人、SSO、Provider Router 预留边界。

## 2. 边界原则

### 2.1 Must

1. P0 必须完成业务 DB 接入，固定使用 `MySQL + Flyway + MyBatis`，知识库、文档资产、入库任务状态不能只存在 ES/Redis/内存中。
2. P0 必须提供 `KnowledgeBase`、`DocumentAsset`、`IngestionTask` 三类产品对象。
3. P0 搜索和问答必须支持 `kbIds` 范围，避免跨知识库污染结果。
4. P0 预览必须能通过 `segmentId` 稳定打开证据，不依赖入库期短 TTL 缓存。
5. P0 前端需要的导入限制、支持格式、阶段枚举必须由后端能力接口声明，不在前端硬编码。
6. P0 可以使用管理员 token / 本地单用户，但核心表必须预留 `workspaceId/createdBy/updatedBy/ownerId`。
7. P1 再补流式问答、回答模式、最近问题/引用、引用解释、设置页连接测试和更多格式。
8. P2 再补 Workspace 权限、SSO、审计、联网搜索、Provider Router 热切换。

### 2.2 Out of Scope

1. P0 不接 SSO。
2. P0 不做复杂 RBAC，只使用固定系统用户或管理员 token。
3. P0 不做联网搜索，未接 provider 前前端隐藏或置灰。
4. P0 不要求 DOCX/XLSX/CSV/PPTX/ZIP 全量格式完成。
5. P0 不做配置热切换和配置版本回滚。
6. P0 不把大段全文 chunk 存进 DB，segment/chunk 仍以 ES 为主。
7. P0 不做复杂审计，只保留后续 `activity_event/audit_log` 边界。

### 2.3 Phase 4 启动前置任务

以下任务是进入 Phase 4 P0 主开发前必须先完成或明确验收口径的启动门槛。

状态说明：

- `DONE`：文档口径或方案已冻结。
- `IMPLEMENTED`：代码/配置已落地，仍需在具备 MySQL/Docker 的运行环境做启动级验收。

| 前置ID | 状态 | 前置任务 | 说明 | 验收标准 |
|---|---|---|---|---|
| PRE-01 | DONE | 固定 DB 技术栈 | Phase 4 P0 使用 `MySQL + Flyway + MyBatis`，不做 PostgreSQL/SQLite 兼容层 | PRD、Epic、任务卡中 DB 选型一致 |
| PRE-02 | IMPLEMENTED | MySQL 基础设施接入 | 增加 MySQL driver、数据源配置、docker-compose MySQL 服务和 volume | 本地 `docker compose` 可启动 MySQL；后端能连通数据库 |
| PRE-03 | IMPLEMENTED | Flyway migration 基线 | 新增 `src/main/resources/db/migration`，建立 P0 核心表 migration | 空库启动后自动生成 `knowledge_base/document_asset/ingestion_task/ingestion_task_item` |
| PRE-04 | IMPLEMENTED | MyBatis 基线 | 增加 MyBatis 配置、mapper 扫描和基础 repository 约定 | 能通过 mapper 完成一张核心表的插入、查询、更新测试 |
| PRE-05 | IMPLEMENTED | 固定用户上下文 | P0 继续使用管理员 token / 单用户，注入 `workspaceId=default`、`userId=system` | 业务写入表时能自动填充 `workspace_id/created_by/updated_by` |
| PRE-06 | IMPLEMENTED | 统一错误契约 | Phase 4 API 返回稳定 code/message/traceId/details | 前端可区分 401、404、400、409、500 |
| PRE-07 | DONE | `kbId` 索引改造方案 | 明确 `kb_segment` 增加 `kbId` 的 mapping、新索引或 reindex 策略 | 文本/图片 segment 写入时都能带 `kbId` |
| PRE-08 | DONE | 搜索与对话范围策略 | 明确无 `kbIds` 时的默认行为，以及 ES filter 的实现位置 | 指定知识库搜索/问答不会命中其他知识库内容 |
| PRE-09 | DONE | 统一入库 facade 方案 | 明确如何复用现有文本/图片任务，同时把任务状态同步落 DB | 前端只调用 `/api/v1/kbs/{kbId}/ingestion-tasks` |
| PRE-10 | DONE | P0 验收链路冻结 | 固定从创建知识库到引用预览的最小验收链路 | 验收文档覆盖创建知识库、导入、文档状态、搜索、问答、预览、首页聚合 |

#### PRE-07 `kbId` 索引改造执行约束

`kbId` 必须作为 `kb_segment` 的一等字段进入 ES mapping 和 Java domain model，不能只存在 DB 的 `document_asset` 中。

落点：

- `Segment` 增加 `kbId`。
- `KbSegmentDocument` 增加 `kbId` keyword 字段。
- `es-kb-segment-mapping.json` 增加 `kbId` keyword mapping。
- `TextSegmentIndexWriter` 和 `ImageSegmentIndexWriter` 写入 segment 时必须携带 `kbId`。
- `KbSegmentBulkWriter` 写 ES 文档时透传 `kbId`。
- 预览、neighbors 查询仍以 `segmentId/assetId` 为主，但后续权限校验必须能从 segment 反查 `kbId`。

索引策略：

- P0 使用新索引版本承载 `kbId` 字段，不在旧索引原地混跑。
- 本地开发允许重建 `kb_segment` 索引。
- 若已有历史数据，必须通过 backfill 或重新入库补齐 `kbId`；无法确认所属知识库的数据不得进入带 `kbIds` 的搜索结果。

验收：

- 任意文本或图片入库成功后，ES segment 文档中存在非空 `kbId`。
- 指定 `kbIds=[kb_a]` 搜索时，不返回 `kb_b` 的 segment。

#### PRE-08 搜索与对话范围策略

`kbIds` 过滤必须进入 ES 查询层，不能在 Java 返回结果后过滤。

请求策略：

- 搜索接口新增 `kbIds`。
- 对话消息接口新增 `kbIds`，并在 turn 快照中保存 `kbScope`。
- P0 前端应始终传当前知识库 ID。
- 后端收到空 `kbIds` 时，P0 默认查询当前用户可见的全部 ACTIVE 知识库；如果当前用户无可用知识库，返回空结果而不是退化为全库无边界查询。

实现约束：

- BM25 查询使用 bool filter：`terms kbId`。
- vector knn 查询也必须带 filter，确保召回阶段即完成知识库隔离。
- RRF、rerank、asset 聚合只处理已通过 `kbIds` filter 的候选。

验收：

- 搜索和对话问答都不能引用未选择知识库的内容。
- `retrievalTrace` 中记录 `kbScope`，便于排查范围问题。

#### PRE-09 统一入库 facade 方案

前端只面向知识库维度提交入库任务，不直接调用文本/图片两套旧接口。

统一入口：

```text
GET  /api/v1/ingestion/capabilities
POST /api/v1/kbs/{kbId}/ingestion-tasks
GET  /api/v1/kbs/{kbId}/ingestion-tasks
GET  /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}
POST /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed
```

执行策略：

- facade 先创建 `ingestion_task` 和 `ingestion_task_item` DB 记录。
- 为每个文件创建或复用 `document_asset`。
- 根据文件类型路由到现有 `TextAssetIngestionService` 或 `ImageIngestionService` 的内部处理能力。
- 每个阶段同步更新 DB：`UPLOAD -> PARSE -> CHUNK -> EMBED -> INDEX -> ASKABLE`。
- Redis 可继续用于任务锁和执行中瞬时状态，但 DB 是状态查询的权威来源。

验收：

- 服务重启后仍能查询入库任务、任务项和文档状态。
- 文本和图片都能通过统一任务接口进入处理链路。

#### PRE-10 P0 验收链路

P0 后端验收按固定顺序执行：

```text
1. 获取管理员 token
2. 创建知识库
3. 查询导入能力
4. 上传/导入 PDF/TXT/MD/IMAGE 到指定知识库
5. 查询入库任务状态
6. 查询文档列表和文档状态
7. 在指定知识库内关键词检索
8. 创建会话并在指定知识库内问答
9. 点击 resultCard/citation 的 segmentId 打开预览
10. 查询首页聚合
```

P0 验收必须覆盖：

- 401 未授权。
- 知识库不存在。
- 入库任务失败。
- 指定知识库无结果。
- preview URL 过期后重新请求。
- `kbIds` 隔离：同一 query 在不同知识库下返回不同结果，且不串库。

## 3. Epic 拆分

| Epic | 目标 | 覆盖 PRD | 优先级 |
|---|---|---|---:|
| E0 | 协议、DB 与鉴权基线 | 3, 5, 6, 9 | P0 |
| E1 | 知识库与文档资产产品模型 | 6, 7.1, 7.2 | P0 |
| E2 | 入库任务与导入能力收口 | 7.2, 8 | P0 |
| E3 | 检索、问答与预览闭环 | 7.3, 7.4, 7.5 | P0 |
| E4 | Ask First 首页与体验聚合 | 7.1, 7.7 | P0/P1 |
| E5 | 设置、Provider 与外观偏好 | 7.6, 9 | P1/P2 |
| E6 | 文档格式扩展 | 8 | P1/P2 |
| E7 | 账号、权限与企业集成 | 3.5, 3.6, 9 | P1/P2 |
| E8 | 验收、文档与联调 | 10, 11, 13 | P0 |

## 4. 任务卡明细

## E0：协议、DB 与鉴权基线

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-00 | DONE | 产品化接口与路由基线确认 | 无 | API 路由清单、页面路由清单、核心 DTO 字段清单 | 6 个页面对应的后端接口范围固定；`kbId/kbIds/assetId/segmentId/taskId` 命名统一 |
| Q4-01 | IMPLEMENTED | MySQL + Flyway + MyBatis 基线 | Q4-00, PRE-01 | MySQL 连接配置、Flyway migration 机制、MyBatis mapper 基线、启动健康检查 | 服务重启后知识库、文档、任务数据不丢失；本地开发空库可自动初始化 schema |
| Q4-02 | IMPLEMENTED | 核心表结构落地 | Q4-01, PRE-03 | `knowledge_base/document_asset/ingestion_task/ingestion_task_item` migration | 表字段符合 `qanything方向DB表结构设计.md`；包含 workspace 和 created/updated 预留字段 |
| Q4-03 | IMPLEMENTED | 管理员 token / 本地单用户认证 | Q4-01 | 鉴权过滤器、固定用户上下文、401 错误结构 | 未带 token 返回 401；业务写入能填充固定 `createdBy/workspaceId` |
| Q4-04 | IMPLEMENTED | 统一 API 错误码与状态契约 | Q4-03 | API error schema、全局异常处理、traceId | 前端能区分 unauthorized、not found、validation、task failed、server error、timeout |

## E1：知识库与文档资产产品模型

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-05 | IMPLEMENTED | KnowledgeBase CRUD 基础版 | Q4-02, Q4-03 | `POST/GET/PATCH/DELETE /api/v1/kbs` | 可创建、列表、详情、重命名、归档/删除知识库；归档后默认列表不展示 |
| Q4-06 | IMPLEMENTED | KnowledgeBase 统计接口 | Q4-05 | `GET /api/v1/kbs/{kbId}/stats` | 返回文档数、segment 数、最近入库状态、最近更新时间；计算中有稳定空值 |
| Q4-07 | IMPLEMENTED | DocumentAsset 列表与详情 | Q4-02, Q4-05 | `GET /api/v1/kbs/{kbId}/documents`、详情接口 | 文档列表支持分页；展示文件名、类型、大小、状态、失败原因、segment 数 |
| Q4-08 | IMPLEMENTED | 文档去重字段与 fileHash 策略 | Q4-07 | SHA-256 计算策略、`file_hash` 写入逻辑、去重判断 | `fileHash` 由后端基于内容计算；同知识库重复文件可识别，不依赖 OSS etag |
| Q4-09 | IMPLEMENTED | 文档删除基础能力 P1 | Q4-07, Q4-18, Q4-20 | `DELETE /api/v1/kbs/{kbId}/documents/{assetId}` | 删除后文档列表不可见，搜索和问答不再命中该文档；失败时状态可排查 |

实施说明：
- Q4-08 已落后端 SHA-256 计算服务与同知识库 `file_hash` 查询能力；导入链路的写入接入在 Q4-11/Q4-12 统一入库任务中承接。
- Q4-09 当前执行 DB 软删除并同步清理 ES segment；OSS 原始文件/预览文件清理依赖 Q4-18/Q4-20 的文件生命周期接入。

## E2：入库任务与导入能力收口

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-10 | TODO | 导入能力声明接口 | Q4-02 | `GET /api/v1/ingestion/capabilities` | 返回支持格式、大小限制、单批次数量、去重策略、入库阶段枚举 |
| Q4-11 | TODO | 统一知识库入库任务接口 | Q4-05, Q4-10 | `POST/GET /api/v1/kbs/{kbId}/ingestion-tasks`、任务详情 | 前端不感知文本/图片两套 batch task；上传后能在知识库下看到任务 |
| Q4-12 | TODO | 入库任务项状态与进度持久化 | Q4-11 | task item 状态流转、stage、progress、失败原因 | 单个文件可展示 UPLOAD/PARSE/CHUNK/EMBED/INDEX/ASKABLE 阶段和失败原因 |
| Q4-13 | TODO | 失败项重试与失败批量重试 | Q4-12 | item retry、retry failed 接口 | 失败项可重试；无失败项时接口安全返回当前任务状态 |
| Q4-14 | TODO | reparse / reembed 闭环 P1 | Q4-12, Q4-07 | `POST /documents/{assetId}/reparse`、`reembed` | 操作会生成新任务；完成后文档 parse/index/segment 状态更新 |

## E3：检索、问答与预览闭环

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-15 | TODO | 搜索请求支持 `kbIds` | Q4-05, Q4-07 | `KbSearchQueryDTO.kbIds`、ES filter | 指定知识库搜索不会命中其他知识库文档；无 `kbIds` 策略明确 |
| Q4-16 | TODO | 搜索筛选、分页与 facets P1 | Q4-15 | `assetTypes/dateRange/hitTypes/cursor/sort/facets` | 搜索页可筛选文件类型、时间、命中类型；分页结果稳定 |
| Q4-17 | TODO | 搜索页生成答案 P1 | Q4-15, Q4-20 | `POST /api/v1/search/kb-answer` 或 `withAnswer=true` | 答案必须绑定引用；生成失败时仍返回检索结果 |
| Q4-18 | TODO | 对话问答支持 `kbIds` | Q4-15 | conversation message 请求字段、检索链路 kb filter | 指定知识库问答不会引用其他知识库文档；回答返回 `kbScope` |
| Q4-19 | TODO | SSE 流式问答 P1 | Q4-18, Q4-04 | `POST /api/conversations/{sessionId}/messages/stream` | 支持 trace、delta、citations、done、error 事件；失败有明确 error event |
| Q4-20 | TODO | 预览接口稳定接入 | Q4-07, Q4-15 | `GET /api/v1/preview/segments/{segmentId}` 增强与错误码 | PDF/TXT/MD/IMAGE 可按 segment 定位；`previewUrl` 不持久化、不打印日志 |
| Q4-21 | TODO | 预览 refresh 与 neighbors 增强 P1 | Q4-20 | refresh 策略、neighbors 接口或 GET 复用 | URL 过期自动重新请求一次；surrounding chunks 可用于上下文展示 |
| Q4-22 | TODO | 预览页引用解释 P1 | Q4-17, Q4-20 | `sourceQuestion/answerClaim/citationIndex/citationReason` | 能解释引用和答案结论关系；缺失时前端可隐藏增强区域 |

## E4：Ask First 首页与体验聚合

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-23 | TODO | Ask 首页聚合基础接口 | Q4-05, Q4-07, Q4-11 | `GET /api/v1/home/summary` | 一次返回常用知识库、最近导入摘要、最近问题/引用空数组或数据 |
| Q4-24 | TODO | 最近问题接口 P1 | Q4-18 | `GET /api/v1/activity/recent-questions` | 用户提问后可在首页展示最近问题；无数据返回空数组 |
| Q4-25 | TODO | 最近引用接口 P1 | Q4-20, Q4-22 | `GET /api/v1/activity/recent-citations` | 打开引用后可展示最近引用；引用记录包含来源文件和 segment |
| Q4-26 | TODO | activity_event 轻量事件表 P1 | Q4-01 | `activity_event` migration 与事件写入 | 可记录 QUESTION_ASKED、CITATION_OPENED、DOCUMENT_IMPORTED、SEARCH_EXECUTED |
| Q4-27 | TODO | 前端状态聚合契约确认 | Q4-23, Q4-04 | 首页 loading/empty/error 字段约定 | 聚合接口部分字段缺失时，首页能降级渲染，不阻断提问入口 |

## E5：设置、Provider 与外观偏好

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-28 | TODO | 设置页 capabilities 与 providers 查询 P1 | Q4-04 | `GET /settings/capabilities`、`GET /settings/providers` | 可看到当前模型、OCR、对象存储、检索能力状态；API Key 不明文返回 |
| Q4-29 | TODO | 检索参数查询与可热更新项 P1 | Q4-28 | `GET/PATCH /settings/search` | 阈值、RRF、rerank window 可热更新；影响旧数据的配置有明确提示 |
| Q4-30 | TODO | Provider 连接测试 P1 | Q4-28 | `POST /settings/test-connection` | 至少支持模型、OCR、对象存储一种连接测试；失败返回可读原因 |
| Q4-31 | TODO | light/dark/system 外观偏好 P1 | Q4-04 | 本地偏好策略或 `GET/PATCH /settings/preferences` | 切换主题不影响页面状态；跨设备同步不是第一版必选 |
| Q4-32 | TODO | Provider Router 与配置版本 P2 | Q4-28, Q4-29 | provider registry、配置版本、回滚策略 | 可热切配置无需重启；不可热切配置有生效策略和 reembed/reindex 提示 |

## E6：文档格式扩展

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-33 | TODO | DOCX 解析入库 P1 | Q4-11, Q4-20 | DOCX parser、heading/段落/表格提取 | DOCX 可入库、检索、问答、预览定位；失败原因可见 |
| Q4-34 | TODO | XLSX / CSV 解析入库 P1 | Q4-11, Q4-20 | 表格 parser、sheet/表头/行列定位 | 表格内容可检索和问答；结果包含 sheet、行列等定位信息 |
| Q4-35 | TODO | URL 导入 P1 | Q4-11 | URL 抓取、正文抽取、标题层级、失败重试 | URL 导入生成文档资产；可搜索问答；抓取失败原因可见 |
| Q4-36 | TODO | PPTX 解析入库 P2 | Q4-33 | PPTX parser、slide/备注/图片 OCR | PPTX 可按页定位，文本和图片 OCR 可进入检索 |
| Q4-37 | TODO | ZIP 批量导入 P2 | Q4-11, Q4-33, Q4-34 | ZIP 解包、格式过滤、批量任务生成 | ZIP 内不支持格式被明确跳过并展示原因 |

## E7：账号、权限与企业集成

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-38 | TODO | 本地账号密码 P1 | Q4-03, Q4-02 | `user_account`、登录、退出、密码哈希、session/token | 不同用户数据能按 `createdBy` 区分；不要求完整 workspace 权限隔离 |
| Q4-39 | TODO | Workspace 与成员角色 P2 | Q4-38 | `workspace/workspace_member`、OWNER/ADMIN/EDITOR/VIEWER | VIEWER 不能删除；EDITOR 可导入；ADMIN 可配置 workspace |
| Q4-40 | TODO | SSO / 企业身份集成 P2 | Q4-39 | OIDC/企业微信/飞书/LDAP 至少一种方案 | 可通过外部身份登录并映射本地用户；失败有可排查日志 |
| Q4-41 | TODO | 审计日志 P2 | Q4-39 | `audit_log`、审计查询接口 | 可按用户、资源、时间检索登录、导入、删除、设置修改等事件 |
| Q4-42 | TODO | 联网搜索 provider P2 | Q4-17, Q4-28 | `POST /api/v1/search/web`、provider 配置、来源标识 | 未配置 provider 时前端置灰；启用后答案能区分本地知识库和联网来源 |

## E8：验收、文档与联调

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| Q4-43 | TODO | P0 后端接口验收文档 | Q4-05, Q4-11, Q4-15, Q4-18, Q4-20, Q4-23 | REST API 验收文档、Postman/HTTP 示例 | 覆盖创建知识库、导入、任务状态、搜索、问答、预览、首页聚合 |
| Q4-44 | TODO | P0 前后端联调清单 | Q4-04, Q4-43 | 联调变量、错误态、空态、权限态 checklist | 6 个页面都有 loading/empty/error/unauthorized/retry 处理 |
| Q4-45 | TODO | P0 端到端验收用例 | Q4-43, Q4-44 | 10-20 条问答/搜索样例与验收记录 | 入库成功后可在指定知识库搜索和问答；引用能打开预览 |
| Q4-46 | TODO | 性能与稳定性验收 | Q4-20, Q4-45 | P95 指标记录 | 对话页面首屏 P95 < 2.5s；预览接口 P95 < 800ms；文档列表分页稳定 |
| Q4-47 | TODO | 文档同步与实现记录 | Q4-45 | PRD、DB 表结构、接口文档、任务卡状态更新 | 变更后的接口、字段、错误码、表结构和验收结果均有记录 |

## 5. 依赖关系

```text
E0 协议、DB 与鉴权基线
  ├─> E1 知识库与文档资产产品模型
  │     ├─> E2 入库任务与导入能力收口
  │     ├─> E3 检索、问答与预览闭环
  │     └─> E4 Ask First 首页与体验聚合
  ├─> E5 设置、Provider 与外观偏好
  └─> E7 账号、权限与企业集成

E2 入库任务
  └─> E6 文档格式扩展

E3 检索、问答与预览
  ├─> E4 最近问题 / 最近引用
  ├─> E5 联网搜索 provider
  └─> E8 验收、文档与联调
```

## 6. 建议实施里程碑

### M1：P0 数据底座与知识库模型

目标：先把产品主数据落到 DB，避免前端继续依赖临时状态。

范围：

- Q4-00 ~ Q4-08
- Q4-10 ~ Q4-13

验收：

- 能创建知识库。
- 能上传或导入资料到指定知识库。
- 能看到文档资产和任务状态。

### M2：P0 搜索、问答、预览闭环

目标：完成 Ask First 最小可用路径。

范围：

- Q4-15
- Q4-18
- Q4-20
- Q4-23
- Q4-43 ~ Q4-45

验收：

- 入库成功后可在指定知识库内搜索和问答。
- 问答结果包含可点击引用。
- 引用可以进入预览页并定位证据。

### M3：P1 产品体验增强

目标：从“能用”升级到“像产品”。

范围：

- Q4-09
- Q4-14
- Q4-16 ~ Q4-17
- Q4-19
- Q4-21 ~ Q4-31
- Q4-33 ~ Q4-35
- Q4-38

验收：

- 支持删除、重解析、重向量化。
- 支持流式问答、搜索生成答案、最近活动、引用解释。
- 支持 DOCX/XLSX/CSV/URL。
- 设置页可以做连接测试。

### M4：P2 企业与成熟能力

目标：补齐团队化和企业部署能力。

范围：

- Q4-32
- Q4-36 ~ Q4-37
- Q4-39 ~ Q4-42

验收：

- 支持 Workspace 权限模型、SSO、审计。
- 支持 Provider Router 和联网搜索。
- 支持 PPTX/ZIP。

## 7. P0 验收清单

1. 用户可以创建知识库并上传一批文件。
2. 用户可以看到文档入库状态和失败原因。
3. 入库成功后，可以在指定知识库内搜索和问答。
4. 搜索和问答不会命中未选知识库的数据。
5. 问答结果包含可点击引用。
6. 引用可以定位到 PDF 页码、文本片段或图片 bbox。
7. `previewUrl` 不持久化、不写日志。
8. 导入页展示的格式、大小、数量限制来自后端。
9. 首页聚合接口可以支撑 Ask First 首屏渲染。
10. 前端 6 个页面具备统一 loading、empty、error、unauthorized、retry 状态。
