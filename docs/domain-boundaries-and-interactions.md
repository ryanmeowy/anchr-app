# Anchr 领域边界与交互决策

## 决策信息

- 对应任务：ANCHR-201
- 状态：已接受
- 源码基线：`dev/clean-up@4dfa6b31401c5dce0d8ed122cc913b7689d363d6`
- 决策日期：2026-07-29
- 适用范围：`anchr-app` 模块化单体

## 结论

项目保留为一个模块化单体，按业务一致性和状态所有权划分为五个业务上下文，加一个技术边界：

1. Knowledge Content：当前 `kb + ingestion`；
2. Retrieval：当前 `search`；
3. Ask：当前 `conversation`，包含 Agent 子模块；
4. Activity：顶层 `activity` 下的 Activity/Recent 代码；
5. Capability & Provider Configuration：当前 `settings + integration` 的配置和 Adapter；
6. Auth / Technical Kernel：当前 `auth + common` 的稳定技术能力。

`kb` 与 `ingestion` 不拆成两个 bounded context。它们共同维护 Asset、Ingestion Item 和 index generation 的同一组业务不变量，可以在同一个本地事务中直接协作。真正需要跨领域契约的是 Knowledge Content、Retrieval、Ask、Activity 和 Capability 之间的调用。

本决策不引入 ArchUnit，不要求把所有 Application DTO 重写，不创建通用 Command Bus、Event Bus 或 Outbox 平台，也不要求立即搬迁现有 package。

## 领域地图

| 上下文 | 当前代码位置 | 核心职责 | 类型 |
|---|---|---|---|
| Knowledge Content | `kb`、`ingestion` | 知识库、文档资产、导入批次、单 Item 执行、去重、解析和 Asset generation 激活 | 核心域 |
| Retrieval | `search` | Segment 索引投影、物理索引和 alias、召回、generation 可见性过滤、RRF、rerank、证据和 Preview | 核心域 |
| Ask | `conversation`、其下 `agent` | Session/Turn、证据消费、回答生成、Agent Task/Run/Step、工具编排和流式应用事件 | 核心域 |
| Activity | `activity` | 用户行为记录和 Recent 读模型 | 支撑域/读模型 |
| Capability & Provider Configuration | `settings`、`integration` | 模型/存储配置、serving provider、AI/Docling/OSS client 解析与适配 | 支撑域 |
| Auth / Technical Kernel | `auth`、`common` | Token、用户请求上下文、错误信封、ID、加密和 Web 横切设施 | 技术边界 |

### 上下文关系

```text
                       ┌──────────────────────────┐
                       │ Capability / Providers   │
                       │ AI · Docling · Storage   │
                       └───────────┬──────────────┘
                                   │ outbound capability
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
┌────────────────────────┐  sync  ┌──────────────┐  sync  ┌──────────────┐
│ Knowledge Content      │◄───────►│  Retrieval   │◄───────│     Ask      │
│ KB · Asset · Ingestion │ query / │ Segment · ES │ query  │ Conv · Agent│
└──────────┬─────────────┘ command └──────┬───────┘        └──────┬───────┘
           │ reliable cleanup             │                       │
           └───────────────────────────────┘                       │
                    after-commit best-effort                       │
           ┌───────────────────────────────────────────────────────┘
           ▼
┌────────────────────────┐
│ Activity / Recent      │
└────────────────────────┘
```

## 状态所有权

### 数据库、索引和对象

| 状态/存储 | 所有者 | 说明 |
|---|---|---|
| `knowledge_base` | Knowledge Content | KB 身份、状态和统计；统计是由 Asset/Item 结果刷新的业务投影 |
| `asset` | Knowledge Content | 文档身份、对象引用、解析/索引状态、删除状态、`active_index_generation` |
| `ingestion_task` | Knowledge Content | 创建幂等、批次身份和汇总计数 |
| `ingestion_task_item` | Knowledge Content | 单文档执行状态、stage/progress、目标 generation、失败和重试结果 |
| `outbox_event` | Knowledge Content infrastructure | 当前仅承载 Asset 删除和 generation 退休的可靠清理消息，不是独立业务域 |
| ES Segment、物理索引、alias | Retrieval | Segment 投影、mapping、向量空间和索引拓扑；不能反向成为 Asset 的事实来源 |
| `conversation_session`、`conversation_turn` | Ask | 会话和问答轮次；scope/citation 是当时的请求或回答快照 |
| `agent_task`、`agent_run`、`agent_step` | Ask | Agent 异步任务、运行和步骤；不并入 Session 大聚合 |
| `activity_event` | Activity | 用户行为和 Recent 视图来源；丢失不影响主业务正确性 |
| `capability_config`、`storage_config` | Capability | desired/serving provider 配置和存储配置 |
| Redis access token | Auth | Token 身份、角色和有效期 |
| 原文件、预览文件、内嵌图片对象 | Knowledge Content | 业务生命周期归 Asset/generation；签名、上传和删除由 Storage capability 实现 |

### 聚合与一致性边界

| 边界 | 决策 |
|---|---|
| `KnowledgeBase` | 独立聚合，不加载全部 Asset；名称、状态和统计由 Knowledge Content 管理 |
| `Asset` | 独立聚合；删除状态和 active generation 是跨流程可见性的业务门禁 |
| `IngestionTask` | 批次身份和创建幂等边界；Items 列表与计数是查询/汇总投影 |
| `IngestionTaskItem` | 独立执行一致性边界；按 itemId claim、推进、失败和重试，不随 Task 整体保存 |
| `Segment` | Retrieval 索引投影，不是 Asset 内部实体，不随 Asset 聚合加载 |
| `ConversationSession` | Session 自身状态边界；Turn 是关联的独立追加记录 |
| `AgentTask` / `AgentRun` | 分别按自身生命周期持久化；Step 是 Run 的追加 trace |
| Activity/Settings/Auth | 保持读模型或事务脚本，不为形式完整补 Aggregate/Factory/Repository 套件 |

数据库行锁、expected status/stage 和受影响行数仍是跨线程并发的最终门禁。领域方法或 Policy 只能表达已有决策，不能代替数据库 CAS。

## 术语所有权

| 术语 | 所有者 | 唯一含义 |
|---|---|---|
| Asset generation / `active_index_generation` | Knowledge Content | 单个 Asset 当前可见的内容索引代次 |
| target generation | Knowledge Content | 当前 Ingestion Item 正在写入、尚未激活的 Asset generation |
| physical index version | Retrieval | ES 物理索引及 alias 指向的部署版本 |
| embedding profile | Capability 保存配置；Retrieval决定索引兼容性 | 生成同一向量空间所需的 provider/model/dimensions 等指纹 |
| Segment | Retrieval | 从 Asset 内容生成、用于检索和引用的索引投影 |
| evidence | Retrieval 产生，Ask 消费 | Ask 可用于回答和 citation 的只读检索快照 |
| Activity record | Activity | 用于 Recent 展示的行为快照，不是业务事实事件 |

`Asset generation`、`physical index version` 和 `embedding profile` 不得继续统称为“索引版本”。

## 允许的交互模式

### 1. 同上下文直接协作

当两个子模块共同维护同一不变量、需要同一 MySQL 事务时直接协作。Catalog 与 Processing 可以共同使用 Asset、Ingestion Item Repository 和事务协调器，不为 `kb ↔ ingestion` 每次调用增加 Port。

### 2. 同步 Query

调用方必须立即得到只读事实时使用。Query 返回按用例裁剪的 immutable snapshot，不返回 Repository、Record 或对方聚合。

当前目标用途：

- Retrieval 查询可见 KB 和 active generation；
- Ask 查询 KB scope、Document reference 和 active generation；
- Ask 查询 Retrieval evidence/document content；
- Activity 查询 KB 当前名称。

### 3. 同步 Command

调用方必须知道执行结果才能继续本地状态迁移时使用。

当前目标用途：

- Knowledge Content 请求 Retrieval 幂等写入目标 generation，收到 write receipt 后才能尝试激活；
- Capability 请求 Retrieval 部署 embedding profile；
- Retrieval 完成 alias 切换后请求 Capability 激活 serving profile。

### 4. Application Process Coordinator

跨 MySQL、ES 或 provider 的流程由拥有最终业务状态的上下文编排：

- 文档 generation 写入/激活由 Knowledge Content 编排；
- 物理索引重建、alias 切换和 profile 兼容由 Retrieval 编排。

Coordinator 不能宣称 MySQL 与 ES 构成同一个可回滚事务。

### 5. 可靠 Integration Event

本地状态已经提交，后续副作用允许延迟但必须重试时使用。当前仅用于 `AssetDeleted` 和 `AssetGenerationRetired`，通过 Knowledge Content 的 Outbox 触发 Retrieval/Storage 幂等清理。

### 6. after-commit best-effort 通知

丢失不影响业务正确性时使用。QUESTION、SEARCH、IMPORT、CITATION Activity 在主事务提交后记录，payload 必须提前包含 `userId` 和展示快照；Activity 失败不得回滚问答、检索或导入。

### 7. outbound capability port

AI generation、embedding、rerank、Docling 和 Storage 使用消费上下文按用例定义的窄 Port。Adapter 可以在语义完全相同时实现多个 Port，但不创建覆盖所有模型和存储操作的万能接口。

## 公开契约与防腐层规则

1. 提供方在 `application.api` 暴露稳定的 Command/Query/Result，作为模块化单体内的 Published Language；不暴露 Repository、聚合或 REST DTO。
2. 调用方若需要 scope 求交、错误翻译、modality 或结果模型转换，在自己的 `application.acl` 放一个具体类。ACL 不再增加同义接口，避免形成“接口 → ACL 接口 → provider 接口”的空转层。
3. Knowledge Content 与 Retrieval 的查询方向采用 `SearchKnowledgeAcl → KnowledgeContentQueryApi`；写入和清理方向采用调用方具体 ACL 直接调用 Retrieval Application API，不增加同义 outbound port/adapter 空转层。
4. Ask → Knowledge Content 使用 `ConversationKnowledgeAcl`，Ask → Retrieval 使用 `ConversationRetrievalAcl`；它们只翻译 Ask 自己的语义，不复制提供方业务实现。
5. 各上下文 → Activity 已按 `调用方具体 ACL → ActivityRecordApi/ActivityQueryApi` 收口；Activity Published Language 不接收调用方 REST DTO、Repository model 或任意业务 Map。
6. 业务上下文之间不建立 Shared Kernel。`common` 仅保留稳定技术原语，不能成为跨业务模型的堆放区。

### ANCHR-202 已实施调用链

```text
ConversationService
  → ConversationKnowledgeAcl
  → KnowledgeContentQueryApi

ConversationRetrievalOrchestrator
  → ConversationRetrievalAcl
  → RetrievalHitQueryApi
  → RetrievalQueryServiceImpl
  → SearchKnowledgeAcl
  → KnowledgeContentQueryApi

SearchController
  → SearchRestAssembler
  → RetrievalPageQueryApi
  → RetrievalQueryServiceImpl
  → SearchRestAssembler
  → 原 SearchPageDTO
```

公共搜索仍由 `SearchController` 记录 `SEARCH_EXECUTED`；Conversation 内部只调用 Hit API，因此不会记录公共搜索行为。Preview 保留 Retrieval 自己的错误和 URL 构造，只把 KB/Document/generation 查询改走 `SearchKnowledgeAcl`。

## 当前交互清单与目标

以下按业务调用流归类当前顶层 package 的跨模块 import。统计用于固定本次源码基线，不作为 CI 规则。`common` 是技术依赖，因此不列入下表。

| 当前 package 边 | import 数 | 文件数 | 判断 |
|---|---:|---:|---|
| `auth → integration/settings` | 3 | 1 | 205C 已改为 `AuthStorageAcl → StorageRuntimeApi`；Controller 不再读取配置或解密 |
| `conversation → kb` | 10 | 5 | Ask 跨域读取 Knowledge Content，交给 204 |
| `conversation → search` | 17 | 10 | Ask 跨域读取 Retrieval，交给 204 |
| `ingestion → kb` | 23 | 7 | 同一 Knowledge Content 内部协作，保留语义 |
| `ingestion → search` | 16 | 6 | Knowledge Content 跨域写 Retrieval，交给 203 |
| `ingestion → integration/settings` | 9 | 2 | 205C 已将 Docling/Storage 直连收口在 `IngestionDoclingAcl/IngestionStorageAcl` |
| `kb → ingestion` | 3 | 1 | Outbox 位于 `kb`；Task Repository 与 `IngestionImagePaths` 是同一 Knowledge Content 内部协作，保留 |
| `kb → search/settings` | 11 | 6 | Preview/Activity/Outbox 已分别经 203/205A/205C 的窄 API/ACL 收口 |
| `search → kb` | 10 | 3 | Retrieval 跨域读取 Knowledge Content，交给 203 |
| `search → integration` | 1 | 1 | 205B 已改为 `RetrievalCapabilityAcl → CapabilityServingConfigApi` |
| `settings → integration/search` | 10 | 1 | 205B 只收口了配置服务触发索引部署；Capability 内部 client 创建保留 |
| `integration → conversation` | 7 | 2 | Adapter 实现 Ask outbound port，方向合理；只校正 contract 层级 |
| `integration → ingestion` | 2 | 2 | Adapter 实现 Processing outbound port，方向合理 |
| `integration → search` | 9 | 7 | Adapter 实现 Retrieval outbound port，方向合理 |
| `integration → settings` | 18 | 12 | `settings + integration` 同属 Capability；Adapter 内部读取 resolver/config 不作为跨域问题 |

| 调用流 | 当前实现 | 目标方式 | 后续主责 |
|---|---|---|---|
| Catalog ↔ Processing | `IngestionApplicationServiceImpl`、`IngestionStageTransactionCoordinator`、`IngestionIndexFinalizer` 直接使用 KB/Asset model 和 Repository | 保留为 Knowledge Content 内部直接协作；只整理命名和事务职责，不增加跨域 Port | 203 |
| Processing → Retrieval index write | 203 已改为 `IngestionRetrievalAcl → RetrievalGenerationIndexApi`；只传 immutable generation snapshot/write receipt，ES replace 位于 MySQL 事务外 | 保持轻量 provider API + caller concrete ACL；不增加同义 Port/Adapter | 203 已完成 |
| Retrieval → Knowledge Content visibility | 202 已改为 `SearchKnowledgeAcl → KnowledgeContentQueryApi`，覆盖 scope、active generation 和 Preview KB/Document；不再读 KB/Asset Repository | 保持现有 provider API + caller ACL；203 只处理 Ingestion 写入和跨存储一致性 | 202 已完成；203 不重做查询边界 |
| Ask → Knowledge Content scope/document | `ConversationServiceImpl` 已通过 `ConversationKnowledgeAcl → KnowledgeContentQueryApi` 处理 KB scope；Agent 的 `AgentRequestContextResolver`、`AgentScopeGuard`、`FindDocumentsTool` 仍直连 Repository | 204 只迁移剩余 Agent document/scope 直连，复用 provider API 或按真实缺口补能力 | 202 已完成部分；204 收尾 |
| Ask → Retrieval search/read | 202 已改为 `ConversationRetrievalAcl → RetrievalHitQueryApi`；`ReadDocumentTool` 仍访问 `SegmentRepository` | 保留检索 ACL；204 只迁移 Agent document content 读取和剩余 Search model 泄漏 | 202 已完成部分；204 收尾 |
| Knowledge Content whole-document preview → Storage | `AssetPreviewServiceImpl → KnowledgeObjectStoragePort`；ConfigDriven Adapter 实现签名 | 保持 Knowledge 自有窄 Port，不依赖 Search Port | 205C 已完成 |
| Retrieval preview → Knowledge Content/Activity | KB/Asset/generation 走 `SearchKnowledgeAcl`；citation record/fetch 走 `SearchActivityAcl`；Retrieval 保留 Segment/Preview 构造 | 保持两个具体 ACL，不重做 Preview 的 Knowledge 查询 | 202、205A 已完成 |
| Ask/Retrieval/Processing → Activity | Conversation/Search/Ingestion/Knowledge 分别经自己的 Activity ACL 调用 Activity Published Language；Append 使用显式用户/时间快照 | 保持 caller ACL + provider API，不加通用事件总线 | 202、205A 已完成 |
| Activity → Knowledge Content | `ActivityQueryApi → ActivityKnowledgeAcl → KnowledgeContentQueryApi.findActiveKnowledgeBases` 获取 ACTIVE KB 名称 | provider 只给通用事实，Activity 自己处理 cursor/去重 | 205A 已完成 |
| Knowledge Content Outbox → Retrieval/Storage/Settings | Retrieval 清理走 `KnowledgeRetrievalCleanupAcl`；图片清理走 `KnowledgeStorageAcl + KnowledgeObjectStoragePort`；Outbox 全链路位于 `kb` | 保持 Knowledge Content 专用 Outbox，不新增全局平台或跨域 API | 203、205C、205D 已完成 |
| Processing → Docling/AI/Storage/Settings | `IngestionTaskProcessorImpl → IngestionDoclingAcl/IngestionStorageAcl`；AI 仍经已有消费 Port | 保持调用方具体 ACL；Docling job/error 和 Storage target/credential 使用 Ingestion 自有 records | 205C 已完成 |
| Capability → Retrieval deployment | `CapabilityConfigServiceImpl → CapabilityRetrievalAcl → RetrievalEmbeddingDeploymentApi`；只传 immutable deployment request | 保持 provider API + caller concrete ACL；Capability 保存 desired/serving，Retrieval 拥有物理重建 | 205B 已完成 |
| Retrieval → Capability activation | alias 切换后经 `RetrievalCapabilityAcl → CapabilityServingConfigApi` 激活 serving config；失败切回旧 alias | 保持窄 activation command，Retrieval 不读取 Capability Repository | 205B 已完成 |
| Provider Adapter → Capability config | `ConfigDriven*Adapter`、resolver、client factory/cache 共同位于 Capability 上下文 | 保留当前实现；消费上下文仍只看到自己的 outbound Port，不额外包装 | 无需跨域改造 |
| Auth upload credential → Capability/Storage | `AuthController → AuthStorageAcl → StorageRuntimeApi` 获取临时凭据 | 保持 `/api/v1/auth/sts` 原路径和 JSON；Controller 不读取配置 Repository | 205C 已完成 |
| Integration Adapter → Ask/Retrieval/Processing ports | `ConfigDrivenGenerationAdapter`、`SpringAiAgentModelAdapter`、embedding/rerank/storage adapters 实现已有消费 Port | 保留这种依赖方向，不为目录对称复制 Adapter | 202、205B 已确认保留 |

## 关键流程所有权

### Asset generation 写入与激活

目标流程由 Knowledge Content 拥有：

```text
1. 既有短 MySQL 事务预留 target generation
2. `IngestionRetrievalAcl → RetrievalGenerationIndexApi` 在事务外幂等 replace target generation
3. `IngestionIndexFinalizer` 短事务重新锁定并校验 Item、Asset、previous generation 和 write receipt
4. CAS 激活 target generation，完成 Item，写入旧 generation 清理 Outbox
5. ES 成功但激活失败：目标 generation 保持不可见，并写入失败 generation 清理 Outbox
6. Outbox 经 `KnowledgeRetrievalCleanupAcl → RetrievalCleanupApi` 幂等清理 ES；对象图片清理保持原逻辑
```

Retrieval 只保证指定 generation 的投影写入结果，不决定哪个 generation 成为 active。

### Embedding profile 部署

Capability 拥有配置，Retrieval 拥有物理索引兼容性：

```text
1. Capability 接收 desired profile 变更
2. Retrieval 验证 mapping/dimensions，重建物理索引并切 alias
3. 只有 alias 切换成功，Retrieval 才请求 Capability 激活 serving profile
4. 任一步失败都保留旧 serving profile 和旧 alias
```

### Storage 与 Docling

Capability 拥有 Storage 配置和临时凭据签发；调用方只消费自己的投影：

```text
AuthController
  → AuthStorageAcl
  → StorageRuntimeApi
  → 原 /api/v1/auth/sts JSON

IngestionTaskProcessorImpl
  → IngestionStorageAcl → StorageRuntimeApi
  → IngestionDoclingAcl → DoclingClient

AssetPreviewServiceImpl / OutboxEventProcessor
  → KnowledgeObjectStoragePort
  → ConfigDrivenStorageAdapter
```

Ingestion 在单次处理开始时捕获 `IngestionStorageTarget`；每次重新提交 Docling job 前重新签发临时凭据，并在提交前校验 endpoint、bucket 与 generation basePath 未变化。Docling job、error 和 retry-after 只在 `IngestionDoclingAcl` 中映射，Processor 的恢复次数、轮询和 ACK 顺序保持不变。

### Knowledge Content 专用 Outbox

`OutboxEvent`、Repository/Mapper、`AssetCleanupOutboxRecorder` 和 `OutboxEventProcessor` 全部由 Knowledge Content 拥有。Asset 删除、generation 激活/退休与 Outbox insert 使用调用方现有短 MySQL 事务；Outbox 写失败继续触发事务回滚。

```text
Asset 删除 / generation 退休
  → AssetCleanupOutboxRecorder
  → outbox_event
  → OutboxEventProcessor
      ├→ KnowledgeStorageAcl + KnowledgeObjectStoragePort
      └→ KnowledgeRetrievalCleanupAcl + RetrievalCleanupApi
```

图片和 Retrieval 清理都要求幂等。Processor 继续使用 claim token、5 分钟 lease、原退避阶梯和最大重试；完成事件按 `Asia/Shanghai` 的 cleanup cron 清理 90 天前数据。不抽全局 Outbox，不把同属 Knowledge Content 的 Ingestion Task/ImagePaths 再包装为跨域 API。

### Activity

Activity 不是可靠业务事件：主流程先完成自己的事务，再发送 best-effort record。若需要不可丢失的审计，应单独立项，不能悄悄把现有 Recent 升级成审计系统。

当前调用链：

```text
ConversationActivityAcl ─┐
SearchActivityAcl ───────┼→ ActivityRecordApi → Activity 实现 → activity_event
IngestionActivityAcl ────┤
KnowledgeActivityAcl ────┘

ActivityController → ActivityQueryApi → ActivityKnowledgeAcl → KnowledgeContentQueryApi
```

QUESTION、SEARCH、IMPORT、CITATION append 失败只丢 Recent；Ingestion 在事务提交后追加。`deleteBySessionId` 和 `deleteCitationOpenedByAssetId` 是同步清理命令，失败继续回滚调用方删除事务。

## 明确不做

- 不引入 ArchUnit 或其他编译/测试期依赖限制插件；
- 不为了目录整齐把 `kb` 与 `ingestion` 强拆；
- 不把 Agent 拆为独立 bounded context 或微服务；
- 不要求所有 REST DTO 退出 Application；只处理真实跨领域协议泄漏；
- 不建立通用 Command Bus、Event Bus、Mediator 或全局 Outbox 平台；
- 不把 Activity、Settings、Auth 富领域化；
- 不在 ANCHR-201 中移动生产类、增加 Port、修改 Bean wiring 或改变业务行为。

## 后续任务输入

| 任务 | 从本决策获得的输入 |
|---|---|
| ANCHR-202 | 已完成 provider Application API + caller concrete ACL、Retrieval Application Result 与 REST adapter |
| ANCHR-203 | Knowledge Content 内部事务边界、Knowledge Content ↔ Retrieval 写入/查询/清理流程 |
| ANCHR-204 | Ask → Knowledge Content/Retrieval 的 scope、document、evidence、content 查询边界 |
| ANCHR-205 | 已完成 Activity read model、Capability/Adapter、Storage/Docling 与 Knowledge Content 专用 Outbox 归属 |
| ANCHR-206 | 前述边界稳定后才允许进行的大 Service 机械拆分 |

任何后续设计若改变状态所有者或交互方式，必须先回答：

1. 哪个上下文拥有最终状态？
2. 调用方是否必须立即得到结果？
3. 失败由谁重试、补偿或降级？
4. 是否真的存在需要防腐层的语义翻译？

无法回答以上问题时，不新增跨领域接口或事件。

## 源码依据

- [`IngestionApplicationServiceImpl`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionApplicationServiceImpl.java)
- [`IngestionStageTransactionCoordinator`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionStageTransactionCoordinator.java)
- [`IngestionIndexFinalizer`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionIndexFinalizer.java)
- [`SegmentBulkWriter`](../src/main/java/com/anchr/core/ingestion/infrastructure/persistence/es/SegmentBulkWriter.java)
- [`RetrievalQueryServiceImpl`](../src/main/java/com/anchr/core/search/application/impl/RetrievalQueryServiceImpl.java)
- [`SearchKnowledgeAcl`](../src/main/java/com/anchr/core/search/application/acl/SearchKnowledgeAcl.java)
- [`KnowledgeContentQueryApi`](../src/main/java/com/anchr/core/kb/application/api/KnowledgeContentQueryApi.java)
- [`SegmentPreviewServiceImpl`](../src/main/java/com/anchr/core/search/application/impl/SegmentPreviewServiceImpl.java)
- [`ConversationRetrievalAcl`](../src/main/java/com/anchr/core/conversation/application/acl/ConversationRetrievalAcl.java)
- [`ConversationKnowledgeAcl`](../src/main/java/com/anchr/core/conversation/application/acl/ConversationKnowledgeAcl.java)
- [`AgentScopeGuard`](../src/main/java/com/anchr/core/conversation/application/agent/tool/AgentScopeGuard.java)
- [`ReadDocumentTool`](../src/main/java/com/anchr/core/conversation/application/agent/tool/ReadDocumentTool.java)
- [`ActivityRecordServiceImpl`](../src/main/java/com/anchr/core/activity/application/impl/ActivityRecordServiceImpl.java)
- [`ActivityQueryServiceImpl`](../src/main/java/com/anchr/core/activity/application/impl/ActivityQueryServiceImpl.java)
- [`OutboxEventProcessor`](../src/main/java/com/anchr/core/kb/application/impl/OutboxEventProcessor.java)
- [`CapabilityConfigServiceImpl`](../src/main/java/com/anchr/core/settings/application/impl/CapabilityConfigServiceImpl.java)
- [`ConfigDrivenEmbeddingAdapter`](../src/main/java/com/anchr/core/integration/ai/adapter/ConfigDrivenEmbeddingAdapter.java)
- [`ConfigDrivenStorageAdapter`](../src/main/java/com/anchr/core/integration/storage/ConfigDrivenStorageAdapter.java)
- [`V1 runtime config`](../src/main/resources/db/migration/V1__create_runtime_config_tables.sql)
- [`V2 knowledge base`](../src/main/resources/db/migration/V2__create_knowledge_base_tables.sql)
- [`V3 ingestion`](../src/main/resources/db/migration/V3__create_ingestion_tables.sql)
- [`V4 activity`](../src/main/resources/db/migration/V4__create_activity_event_table.sql)
- [`V5 outbox`](../src/main/resources/db/migration/V5__create_outbox_event_table.sql)
- [`V6 conversation`](../src/main/resources/db/migration/V6__create_conversation_tables.sql)
- [`V7 agent`](../src/main/resources/db/migration/V7__create_agent_tables.sql)
