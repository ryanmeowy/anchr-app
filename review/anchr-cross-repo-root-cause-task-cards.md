# Anchr 三项目根因修复任务卡

## 文档范围

本文最初基于三个项目下列 `main` HEAD 对真实调用链进行审查；各任务卡的“当前流程与根因”保留该审查基线，“实施与验证记录”则按后续开发工作区的实际实现与验证持续更新。

- `anchr-app`: `02031f9b35966ec98200c3ca0dcd9649cd941bfb`
- `anchr-web`: `35a09a4d5955d336884aa034ad16935268c2a0b8`
- `anchr-docling`: `c36eeb69d899728a803242d03fc93ae3b64bd490`

> 初始审查不把当时的未提交变更视为 `main` 实现；本文当前 ANCHR-106B 的实施记录来自 `anchr-app` 的 `dev/clean-up` 工作区，同样不表示已经合并到 `main` 或发布。

> 2026-07-29 起，ANCHR-201–206 的“当前事实”、范围和验收单独以 `anchr-app` 的 `dev/clean-up@4dfa6b31401c5dce0d8ed122cc913b7689d363d6` 为基线重写；不把工作区中仅新增的质量报告视为源码实现。其他任务仍遵守各自记录的审查或实施基线。

任务拆分原则：

1. 每张卡解决一个明确根因，不以局部重试、吞异常或前端兜底代替状态模型修复。
2. 保持现有成功接口、前端交互、Docling 鉴权和下载安全策略兼容。
3. 将客户端请求幂等、Docling 解析 attempt、Ingestion 调度 attempt 分开建模。
4. 跨 MySQL、Elasticsearch、Docling 的流程必须可恢复、可重放、可观测。

## 任务总览

> 状态按每张卡已经完成的实现与验收范围维护；仍有真实环境验收项的任务不得标记为“已完成”。“已完成”也不等于已经提交、合并或发布。

| ID | 任务 | 状态 | 优先级 | 规模 | 项目 | 依赖 |
|---|---|---|---:|---:|---|---|
| ANCHR-101A | 修复当前单向量结构下图片分块未写入向量 | 已完成 | P0 | S | app | 无 |
| ANCHR-101B | 固化单 embedding 的 Profile 投影与检索契约 | 源码与目标测试已完成；待真实 ES 隔离索引验证与部署验收 | P1 | M | app | 101A、107 |
| ANCHR-101C | 延后 Embedding 配置启用并安全重建物理索引 | app/web 单实例实现已恢复；待真实 ES 验收 | P1 | M | app、web | 101B |
| ANCHR-102 | 建立正确的 HTTP 错误与上传清理契约 | 已完成 | P0 | M | app、web | 无 |
| ANCHR-103 | Ingestion 创建请求幂等与前端恢复 | 已完成 | P0 | M | app、web | 102 |
| ANCHR-104 | 关闭未消费且加密错误的内嵌图片上传链路 | 已完成 | P1 | S/M | app、docling | 101A |
| ANCHR-105 | 重构 Docling attempt 与幂等协议 | 已完成 | P0 | M | app、docling | 104 |
| ANCHR-106 | Ingestion 改为数据库驱动的可恢复状态机 | 已完成 | P0 | XL | app、docling、web | 103、105 |
| ANCHR-106B | 收敛 Ingestion Item 执行模型与持久化边界 | 源码与 V18 已收口；独立 MySQL 8.4 迁移验证通过；待业务库修复失败历史、停机迁移与部署验收 | P0 | L | app | 106 |
| ANCHR-107 | 建立 Asset Segment generation 与 ES 写入幂等一致性 | 源码与本地回归已完成；待 V19 迁移、真实 ES 故障演练与部署验收 | P0 | L | app | 106B |
| ANCHR-109 | 会话列表 keyset 分页与 Session 原子更新 | 已完成 | P1 | M | app、web | 可独立 |
| ANCHR-110 | 文档内嵌图片制品化、独立 Segment 与跨模态检索 | 主体源码与本地回归已完成；失败 attempt 清理已接入既有 Outbox，待真实 OSS/ES、存量 reparse 与部署验收 | P1 | XL | app、docling、web | 104–106B、107、101B、101C |
| ANCHR-201 | 固化领域地图、状态所有权与交互决策 | 已完成 | P1 | S | app | 当前 clean-up 源码基线 |
| ANCHR-202 | 能力提供方 API 与调用方 ACL | 已完成 | P1 | L | app | 201 |
| ANCHR-203 | 收口 Knowledge Content 与 Retrieval 的一致性边界 | 已完成 | P1 | L | app | 201、202，且 101B/101C、106B、107、110 契约稳定 |
| ANCHR-204 | 收口 Ask 剩余的 Knowledge/Retrieval 同步读取边界 | 已完成 | P1 | L | app | 201–203、109 |
| ANCHR-205 | 收口 Activity、Provider/Storage 与专用 Outbox 支撑边界 | 源码与本地回归已完成；待真实 MySQL/OSS/ES 验收 | P2 | XL | app | 201–204、101C、107 |
| ANCHR-206 | 按已稳定用例边界拆分超大 Application Service | 206A–206E 源码与本地回归已完成；206F 待执行 | P2 | XXL | app | 203–205 |

## 任务边界与唯一归属

### 边界规则

1. 每个业务不变量、持久化字段和外部协议只能有一张主责卡；依赖卡只能消费其输出，不得重新定义。
2. 上游卡可以增加完成自身根因修复所需的最小 Port/DTO，但不能顺手完成 ANCHR-201–206 的全局架构迁移。
3. 正确性卡负责确定业务语义和兼容行为；DDD 卡只能在 characterization/contract test 保护下移动边界，不新增业务规则。
4. 同一个 PR 不得同时包含正确性行为变更和纯结构拆分类；确需联合发布时也必须拆为可独立回滚的 commit 和验收报告。
5. `index generation` 专指单个 Asset 的内容版本；`physical index version` 专指 ES 物理索引部署版本，禁止混用字段、状态和任务名称。

### 唯一归属矩阵

| 主责卡 | 唯一拥有 | 只消费 | 明确不负责 |
|---|---|---|---|
| 101A | 当前单向量结构下 IMAGE 的分支前置条件、输入选择和向量回写 | 现有 Asset/Chunk/embedding 接口 | OCR+图片双 dense 写入、检索重构、模型切换 |
| 101B | 单 `embedding` 的 Profile 投影 Policy、图片视觉投影单元、同一模型配置和来源契约 | 现有 BM25/RRF/Rerank；101A 的单载体线上修复；107 的 generation 可见性 | desired/serving profile、物理索引迁移、第二向量字段、文档内嵌图片制品/召回配额、Asset generation |
| 101C | 内存待重建目标、目标模型 Session、全程 JVM 写屏障、alias 切换后启用模型 | 101B 的单向量投影 Policy | 持久化部署状态、分布式租约、增量追平、第二路 KNN、Asset generation |
| 102 | HTTP status/error metadata 与 OSS 清理许可契约 | 各业务卡提供的 errorCode 语义 | 创建幂等、任务恢复和业务重试策略 |
| 103 | `clientRequestId/requestHash` 和创建响应丢失恢复 | 102 的错误元数据 | Docling attempt、任务执行调度、ES 幂等 |
| 104 | 关闭未消费的 embedded-image STS 上传协议 | 现有独立图片 OCR | Docling job 幂等、OCR/图片向量语义、未来 AEAD 实现 |
| 105 | `parseAttempt/sourceRevision/doclingRequestId/doclingJobId` 与 Docling 指纹协议 | 104 关闭后的请求体 | worker lease、阶段调度、Docling 结果消费时序、ES generation |
| 106 | `executionEpoch/stageAttempt/lease/nextActionAt` 与可恢复阶段调度 | 105 的 submit/get/ack 和 parse attempt | 创建请求幂等、Docling 指纹、Asset generation、ES alias |
| 106B | `ingestion_task`、`ingestion_task_item` 两表物理边界；item 当前 phase、lease、attempt、claimVersion 与公开投影的唯一映射；Docling 成功结果保留、消费和 post-index ACK 时序 | 106 已确定的 stage/retry/lease/attempt 语义和 stale-worker 拒绝结果 | 改变任务行为或 stale-worker 结果、Docling ACK HTTP 幂等协议、Asset generation、通用 DDD/Service 拆分 |
| 107 | Asset `indexGeneration`、目标 generation 重写、MySQL/ES 可见性和清理事件 | 106B 的两表当前态边界与 106 的 INDEX stage；现有 outbox 能力 | physical index version、embedding profile、检索融合、Outbox 搬包 |
| 109 | Session 列表 keyset cursor、title CAS、updatedAt 单调更新 | 现有消息/Agent 数据 | 消息历史分页、Agent Activity、Conversation DTO 分层 |
| 110 | `EmbeddedImageArtifact` 契约、`DOCUMENT_IMAGE` Segment、图片对象随 Asset generation 清理、同字段分路召回、父文档聚合和图片命中预览 | 104 的默认关闭门禁；105/106 的稳定 Parse attempt 与 Docling job；106B 的 item 图片目录快照；107 的 generation/ID/事件；101B 的单向量 Policy；101C 的 profile 部署 | 图片专用生命周期、第二向量字段、模型部署状态机、通用 Docling attempt、通用 Outbox 搬包 |
| 201 | 领域地图、表/索引/状态所有权、七类交互方式及决策记录 | 当前 clean-up 源码与正确性卡的既有语义 | 引入 ArchUnit、移类、造 Port、修改业务行为 |
| 202 | 真正跨领域调用的最小 Query/Command/Event contract；核心 HTTP/SSE 的传输适配边界 | 201 的领域地图与现有协议 | 全量 DTO 清洗、每个方法一个 Port、通用 command/event bus |
| 203 | Knowledge Content 内部事务边界；其与 Retrieval 的写入、激活、查询和清理流程 | 101B/101C、106B、107、110 的 generation/profile/segment 语义；202 的 contract 形式 | 把 kb/ingestion 强拆为两个上下文、伪装跨库事务、重定义检索算法 |
| 204 | Ask 对 Knowledge Content、Retrieval 的 scope/evidence 查询契约；Conversation/Agent 的内部生命周期边界 | 109 的 Session CAS；203 的可见性与检索契约 | 把 Agent 拆成独立上下文、跨域 Repository、改变工具/SSE/模型调用顺序 |
| 205 | Activity 读模型、Capability 配置/Adapter、Knowledge Content 专用 Outbox 的合理归属 | 107 的清理事件和 101C 的 profile 部署语义；202–204 的公开能力 | 建通用事件平台、把简单配置富领域化、把所有通知升级为可靠消息 |
| 206 | 稳定边界内的机械职责拆分 | 203–205 的最终边界与 202 的必要传输适配 | 新业务规则、协议/mapping/schema 修改、相关性调参 |

### 依赖交付契约

- 101B 向 101C 交付：按 profile 选择 `TEXT/IMAGE` 输入的单向量 Projection Policy 和唯一向量字段契约；101C 不复制输入选择规则。
- 101B 向 110 交付：按 profile 对 `TEXT/IMAGE` 输入生成同一 `embedding` 字段的 Projection Policy；110 只增加内嵌图片制品和同字段召回分路，不复制投影算法、不增加第二向量字段。
- 104/105/106 向 110 交付：默认关闭的旧上传门禁、稳定 Parse attempt、Docling job 身份和请求快照；110 以版本化图片制品契约重新启用支线，不恢复旧 CBC/裸 URL 协议。
- 107 向 110 交付：Asset generation、目标 generation 重写、激活门禁和旧 generation 清理事件；110 不建立第二套图片 generation。
- 101C 向 110 交付：目标 profile 的安全重建能力；110 的 mapping/存量回填复用该流程，不直接切 alias。
- 105 向 106 交付：幂等的 `submit/get/ack` 和 parse attempt 标识；106 只决定何时调用。
- 106 向 106B 交付：已经验收的阶段、重试、lease、fence、Docling 恢复和公开 DTO 行为；106B 只重排持久化边界与读模型，不重新定义这些行为。
- 106B 向 107 交付：两表中的 item 当前执行态和进入 `INDEX` phase 的 fenced context；107 只增加 generation、目标 generation 重写与索引激活一致性。
- 106B 向 110 交付：当前 parse attempt 的稳定 Docling job 身份和图片目录快照；110 直接消费 `ParseResponse.images[]`，不新增 artifact registry、业务表或图片制品指针列。
- 102 向 103/105/106 交付：通用 HTTP 错误信封；各业务卡只定义自己的 errorCode、retryable 和 accepted 语义。
- 201 只记录业务边界和交互决策，不增加依赖检查插件；202 只为被 203–205 实际使用的跨领域调用建立最小契约；206 最后在不改变行为的前提下拆分类。

### 共享修改面与强制顺序

| 共享修改面 | 可能涉及的卡 | 强制顺序 | 后卡必须保留的前卡契约 |
|---|---|---|---|
| Ingestion create/processor / 后续 stage handler | 101A、103、104、105、106、106B、107、101B、101C、110 | 101A → 104 → 105；102 → 103；两路汇合 → 106 → 106B → 107 → 101B → 101C → 110 | 图片分支/向量回写；创建幂等；关闭 STS 支线；Parse 协议；可恢复状态机；normalized execution/artifact 边界；Asset generation/目标重写；单向量 Policy；profile 部署；内嵌图片投影 |
| `Segment` / `SegmentDocument` / mapping / bulk writer | 107、101B、101C、110 | 107 → 101B → 101C → 110 | Asset generation/目标重写 → 单向量投影契约 → 物理索引部署 → 内嵌图片 schema/回填 |
| `RetrievalQueryServiceImpl` / ES repository | 107、101B、110、202、206 | 107 → 101B → 110 → 202 → 206 | active generation 过滤 → 同一模型与单向量字段 → 图片分路召回/父资产聚合 → Application API 迁移 → 机械拆分 |
| Search/Conversation result DTO 与 Preview | 110、202、204、206 | 110 → 202 → 204 → 206 | 图片命中与父文档预览语义 → 最小 evidence contract → Ask 迁移 → 机械拆分 |
| Capability settings / `SegmentIndexManagerImpl` | 101C、203、205、206 | 101C → 203 → 205 → 206 | 延后启用与单实例重建 → Retrieval 流程所有权 → 配置/Adapter 归位 → 机械拆分 |
| `ConversationServiceImpl` / Session repository | 109、202、204、206 | 109 → 202 → 204 → 206 | keyset/CAS 结果 → 最小契约 → Ask 边界收口 → 机械拆分 |
| Outbox publisher/processor | 107、203、205、206 | 107 → 203 → 205 → 206 | 事件产生语义 → 清理能力边界 → 专用设施归位 → 机械拆分 |

同一修改面的相邻任务不得并行合并。前卡未合并时，后卡只能做设计或隔离 fixture；不得基于临时分支复制实现后再手工拼接。每张后卡的回归测试必须包含表中所有前置契约。

---

## ANCHR-101A：修复当前单向量结构下图片分块未写入向量

**目标：** 在不修改现有单 `embedding` mapping、segment 数量和检索协议的前提下，恢复两种现有图片路径：文本模型使用 OCR 文本向量，多模态模型使用原图视觉向量；不改变文本、PDF、Markdown 的 embedding 行为。

### 当前流程与根因

前端把图片归类为 `IMAGE`；Docling 返回 `chunks[].textPlain`；`DoclingChunkMapper` 将其写入 `ocrText`，因此图片 chunk 的 `chunkText` 正常情况下为空。Processor 对多模态图片虽然先调用原图 embedding，但循环随后统一检查 `chunkText`，图片 chunk 会在选择文本/图片输入之前被跳过。结果是：

- 文本模型没有执行 `ocrText` embedding；
- 多模态模型可能已经产生 image embedding，却没有写回任何 chunk；
- 最终 ES segment 没有向量，但任务可能继续成功。

此外，Mapper 使用 `response.fileType()` 判断图片，Processor 使用 `asset.fileType`，存在双重类型来源。

源码：

- [`anchr-web/src/lib/ingestion-files.ts`](../../anchr-web/src/lib/ingestion-files.ts)
- [`DoclingChunkMapper.java`](../src/main/java/com/anchr/core/ingestion/infrastructure/parser/DoclingChunkMapper.java)
- [`IngestionTaskProcessorImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionTaskProcessorImpl.java)

### 修复方案与范围边界

以数据库 `asset.fileType` 为唯一权威类型：

```text
IMAGE
  ├─ 文本模型：非空 chunk.ocrText → embedding；无 OCR 则不写向量
  └─ 多模态模型：原始图片 → 视觉向量（每个 Asset 只调用一次）
                              → 只写入 Docling 顺序的首个有效 chunk.embedding
其他资产
  └─ chunk.chunkText → embedding
```

1. Mapper 根据 `Asset` 决定写入 `ocrText` 或 `chunkText`。
2. 循环只先过滤 `chunk == null`，随后按 `asset.fileType + embedding capability` 选择分支，再检查该分支真正需要的输入；禁止再用 `chunkText` 作为所有类型的统一前置条件。
3. 非图片只对有效 `chunkText` 生成向量；文本模型图片只对有效 `ocrText` 生成向量。图片无 OCR 是合法状态，对应 chunk 保留但不写向量，不能把它当成解析失败；多模态图片也不依赖 `chunkText/ocrText` 才能使用原图向量。
4. 多模态原图 embedding 每个 Asset 只调用一次，并且只写入 Docling 顺序中的首个有效图片 chunk；其余 OCR chunks 保留 `ocrText` 参与 BM25，但不复制同一个 dense vector。该规则不能假设 Docling 对图片只返回一个 chunk，避免相同原图向量重复占用 KNN topK；image embedding 失败必须使 item 失败。
5. Docling 没有返回任何可用 chunk 时仍按解析失败处理；但已有图片 chunk 只是没有 OCR 文本时允许继续。独立视觉 segment 的最终模型归 ANCHR-101B。
6. 保持 `IMAGE_OCR_BLOCK`、搜索 DTO 和前端筛选不变。
7. 本任务不拆向量字段、不为多模态图片额外生成 OCR dense vector、不改检索融合、不要求全量重建；只重新摄取或补建受影响图片资产。

### 验收

- IMAGE + 文本模型：有效 OCR chunk 均有文本向量。
- IMAGE + 多模态模型：不论 Docling 返回一个还是多个 OCR chunks，都只调用一次原图 embedding，并且恰好一个载体 chunk 写入视觉向量。
- 多模态图片每个 Asset 只调用一次 image embedding，不在 101A 生成第二份 OCR dense vector。
- 同一图片的其他 OCR chunks 不复制视觉向量，不重复占用 Segment 级 KNN 候选窗口。
- 无 OCR 图片在文本模型下允许成功但没有 dense 向量；在多模态模型下仍由原图视觉向量召回。只有完全没有可用 chunk 才判解析失败。
- PDF/TXT/Markdown 的 segment 数量与 embedding 行为不变。

---

## ANCHR-101B：固化单 embedding 的 Profile 投影与检索契约

**目标：** 保留现有单 `embedding` dense vector，让每个物理索引内的全部向量都由同一个 immutable embedding profile 生成；统一 Ingestion、Rebuild 和 Query 对文本、OCR、原图的输入选择，避免三条路径各写一套条件分支后再次漂移。

### 正确的单向量语义矩阵

```text
纯文本 embedding profile
├─ 文本资产：chunkText → embedding
└─ 图片资产：非空 ocrText → embedding；无 OCR 则无 dense 向量

多模态 embedding profile
├─ 文本资产：chunkText → embedding
└─ 图片资产：N 条 OCR chunk → 只写 ocrText，不生成 OCR dense vector
             1 条 IMAGE_VISUAL 投影 → 原始图片 → embedding
```

成立前提：

1. 纯文本与多模态 embedding 配置互斥，同一时刻只有一个 serving profile。
2. 多模态文本请求和图片请求使用同一个已启用的 `modelName/dimensions`；应用不再额外判断模型内部实现。
3. 一个物理索引只包含同一 profile fingerprint 生成的向量；切换模型必须由 101C 重建，不能混写。
4. 当前产品不要求多模态模式下同时执行“OCR dense recall + visual dense recall”；OCR 通过 `ocrText` BM25 和 Rerank 文本参与检索。

ANCHR-110 可以为 `TEXT_CHUNK` 与 `DOCUMENT_IMAGE` 在同一个 `embedding` 字段上分配独立召回预算和阈值，但这不等于双向量字段，也不允许同一内嵌图片同时写 OCR dense vector 与视觉 dense vector。若未来产品要求同一图片的两种 dense signal 同时召回，再独立立项评估双字段或双 document route。

### 当前根因

- Ingestion 和 `SegmentIndexManagerImpl.computeNewEmbedding` 分别实现了相似但独立的资产类型/profile 判断，容易出现修复一处、重建路径仍旧错误。
- `enrichTextEmbeddings` 的命名与职责不符：多模态图片实际使用原图输入。
- 原图向量是 Asset 级信号，Docling OCR chunk 是 Segment 级文本；把同一个原图向量复制到每个 OCR chunk 会重复占用 Segment 级 KNN topK，而只绑定首个 OCR chunk 只是 101A 的兼容方案，不是最终数据模型。
- Query 直接使用当前 active client，而索引只记录 actual profile；缺少一个明确契约把 query projection 与索引 profile 绑定，部署一致性由 101C 修复。
- 当前单 `embedding` mapping 和单路 KNN 本身不是问题，不需要因为资产类型不同拆成两个 dense vector 字段。

源码：

- [`IngestionTaskProcessorImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionTaskProcessorImpl.java)
- [`SegmentIndexManagerImpl.java`](../src/main/java/com/anchr/core/search/application/impl/SegmentIndexManagerImpl.java)
- [`QueryEmbeddingServiceImpl.java`](../src/main/java/com/anchr/core/search/application/impl/QueryEmbeddingServiceImpl.java)
- [`SegmentDocument.java`](../src/main/java/com/anchr/core/search/infrastructure/persistence/es/document/SegmentDocument.java)
- [`es-kb-segment-mapping.json`](../src/main/resources/es-kb-segment-mapping.json)
- [`EsSegmentRepository.java`](../src/main/java/com/anchr/core/search/infrastructure/persistence/es/repository/EsSegmentRepository.java)
- [`EmbeddingProfile.java`](../src/main/java/com/anchr/core/search/domain/model/EmbeddingProfile.java)

### 修复方案

1. 提取纯业务 `EmbeddingProjectionPolicy`，输入至少包含 `assetType/profileCapability/chunkText/ocrText/imageSource`，输出：

```text
EmbeddingProjection {
  sourceType = TEXT | IMAGE,
  source,
  sourceKind = CONTENT_TEXT | OCR_TEXT | ORIGINAL_IMAGE,
  projectionKind = TEXT_CHUNK | IMAGE_OCR_BLOCK | IMAGE_VISUAL
}
```

2. Ingestion 和 physical index rebuild 必须调用同一个 Policy；禁止各自通过 `isImage && isMulti` 再实现一遍。
3. 多模态图片无论 Docling 返回多少 OCR chunks，都产生恰好一条 Asset 级 `IMAGE_VISUAL` 投影；它使用普通 Segment ID，并随 107 的 Asset generation 写入和清理。OCR chunks 保持独立记录，只写 `ocrText/bbox/pageNo`，不复制视觉向量。
4. 纯文本 profile 不产生 `IMAGE_VISUAL` 投影；有文本的图片 OCR chunks 各自使用 `ocrText` 生成现有单 `embedding`，无 OCR 的 chunk 保持无向量。从多模态切到纯文本时由 101C 重建目标索引，不能把旧视觉向量复制到 OCR 记录。
5. Query 固定以 `sourceType=TEXT` 使用当前 serving profile 生成 query vector；多模态 profile 下，该文本 query vector与同一 `embedding` 字段中的文本/图片向量比较。
6. ES mapping 继续只有一个 `embedding`，维度来自 profile；`_source` 普通读取继续排除该字段。
7. BM25 继续检索有正文的 Segment 的 `title/contentText/ocrText`，没有正文的 `IMAGE_VISUAL` 不进入 BM25；基线 KNN 继续检索唯一 `embedding` 字段，再按现有 RRF/Rerank 流程融合。本卡不调整 RRF/Rerank 参数，也不增加按业务类型分配召回配额，该行为归 ANCHR-110。
8. 可增加非向量元数据 `embeddingSourceKind` 用于覆盖率、重建校验和问题定位，但它不能参与决定 profile，也不能替代 physical index metadata。

### 边界

本卡负责单向量投影规则、`IMAGE_VISUAL` 投影单元、Ingestion/Rebuild 共用 Policy、唯一向量字段和输入来源契约；不改变模型启用时序、profile 部署状态、physical index rebuild/alias、Asset generation 或 RRF 参数。`IMAGE_VISUAL` 的可见性和清理消费 107，不在本卡重新定义 generation。ANCHR-110 只能消费本卡 Policy 为文档内嵌图片生成向量，并可在同一字段上增加按 `segmentType` 过滤的召回通道；不得复制或改写投影规则。

搜索 REST 用例保持一次性 Top N，`limit` 范围为 1–10；请求不接收搜索 cursor，响应不返回 `nextCursor`，前端不提供搜索结果“加载更多”。若未来产品明确要求浏览 Top N 之外的结果，必须重新立项定义交互和成本边界，不能预建 Redis snapshot。

101A 只修当前线上分支提前跳过和重复视觉向量的缺陷，使用首个有效 OCR chunk 作为兼容载体；101B 再把 Asset 级视觉信号从 OCR chunk 中拆成唯一 `IMAGE_VISUAL` 投影并收口成统一 Policy。101C 消费 Policy 对目标索引重新生成投影和 `embedding`，但不能复制其输入选择规则。

### 验收

- 纯文本 profile：有效文本 chunk 使用 `chunkText`，有效图片 OCR chunk 使用 `ocrText`；空文本/OCR chunk 不调用 embedding，也不写零向量。
- 多模态 profile：文本 chunk 使用文本输入；每个图片 Asset 恰好一条 `IMAGE_VISUAL` 记录使用原图输入；任意数量 OCR chunks 只保存 `ocrText`，不含 dense vector。
- 一个图片 Asset 不会因 Docling 返回多个 OCR chunks 而在 KNN topK 中出现重复的原图向量记录。
- Ingestion 与 rebuild 对同一 fixture 产生相同 `sourceType/sourceKind`，不存在路径漂移。
- 多模态文本 query 使用 `sourceType=text`，`IMAGE_VISUAL` 使用 `sourceType=image`；两者使用同一个已启用的 `modelName/dimensions` 和同一个 `embedding` 字段。
- ES mapping 仍只有一个 dense vector；没有新增 `textEmbedding/imageEmbedding`。本卡基线保持一条 KNN，后续 ANCHR-110 的同字段分路召回单独验收。
- BM25/RRF/Rerank 的既有输入和顺序不变，相关性基线不回退。

### 实施与验证记录（2026-07-24）

- Ingestion 已按上述矩阵直接生成 `Segment`：普通文本在两种 profile 下都以 `chunkText` 调用 `sourceType=text`；文本 profile 的图片仅对非空 `ocrText` 生成向量；多模态 profile 的图片保留全部 OCR Segment，并按 `assetId + indexGeneration` 额外生成且只生成一条 `IMAGE_VISUAL`，原图只调用一次 `sourceType=image`。图片没有 OCR 时仍可生成视觉向量；图片向量为空会使当前 item 失败。
- Ingestion 与索引重建共用 `EmbeddingProjectionPolicy`。重建到文本 profile 时删除旧 `IMAGE_VISUAL`、按非空 OCR 重新生成文本向量；重建到多模态 profile 时清空 OCR 旧向量，并从原始 `sourceRef` 重新生成一条视觉记录。跨 scroll batch 复用同一个 Planner，不会为同一 `assetId + indexGeneration` 重复生成视觉记录。
- ES 仍只有一个 `embedding` 字段。文本查询固定调用 `sourceType=text`；KNN 仍检索该字段且保留 `IMAGE_VISUAL`。`IMAGE_VISUAL` 不进入 BM25，不改 RRF/Rerank 参数和执行顺序。
- `IMAGE_VISUAL` 可以作为搜索命中展示并通过 `sourceRef` 预览原图，但它没有正文，不进入 Search Answer、普通 Ask 的回答候选或 Agent 可引用证据。资产聚合后每个 TopChunk 保留自己的命中原因；视觉命中排第一时，同资产 OCR 仍可用于回答、引用和追问。全文读取 `listByAssetId` 排除视觉记录；普通 ES `_source` 读取排除 `embedding`。
- `asset.segment_count/indexed_segment_count` 只统计用户可读的 OCR/文本 Segment，不把内部 `IMAGE_VISUAL` 投影计入“片段数量”。删除仍按 Asset 或 generation 执行，视觉记录会与同代数据一起删除。
- 本卡未增加数据库表或 migration，未修改 `anchr-web`、`anchr-docling` 生产代码，也未增加第二向量字段。
- 验证：`mvn test-compile` 通过；101B 的 23 个目标测试类共 88 个测试全部通过；全仓共执行 497 个测试，0 failure、0 error，其中 27 个依赖 Docker/Testcontainers 的测试因当前机器没有 Docker 而跳过。
- 尚未在真实 Elasticsearch 上执行隔离索引的完整 rebuild、alias 前校验、实际 KNN 查询和结果相关性回归，也未执行部署观察。因此本卡当前状态是“源码与目标测试已完成”，不是已经发布；真实 ES 与部署验收完成前不标记为“已完成”。

---

## ANCHR-101C：延后 Embedding 配置启用并安全重建物理索引

**目标：** 管理员选择不同向量空间的 Embedding 配置时，先用目标配置重建索引，重建成功后再把该配置设为 active，避免“新查询模型 + 旧索引向量”混用。

### 当前根因与范围

旧流程在重建前就执行 `capability_config.select` 并刷新共享 client，导致 read alias 仍指向旧索引时，查询和新写入已经开始使用新模型。

本项目当前按单实例运行。本卡只做以下事情：

1. 不提前启用目标 Embedding 配置。
2. 在 JVM 内存保存一个待重建目标和任务进度。
3. 重建期间用现有 `ReentrantReadWriteLock` 的写锁阻止所有索引写入。
4. 重建使用目标配置创建独立 `EmbeddingSession`。
5. 新索引完成校验并切换 alias 后，才启用目标配置并刷新本地 client。

明确不做：数据库部署状态表、physical index 生命周期表、数据库/Redis 写租约、分布式锁、在线增量追平、watermark、影响报告、独立回滚 API、跨进程崩溃恢复。以后确定改为多实例时，再单独评估分布式锁，不在本卡预埋。

源码：

- [`CapabilityConfigServiceImpl.java`](../src/main/java/com/anchr/core/settings/application/impl/CapabilityConfigServiceImpl.java)
- [`ConfigDrivenEmbeddingAdapter.java`](../src/main/java/com/anchr/core/integration/ai/adapter/ConfigDrivenEmbeddingAdapter.java)
- [`SegmentIndexManagerImpl.java`](../src/main/java/com/anchr/core/search/application/impl/SegmentIndexManagerImpl.java)
- [`SegmentIndexWriteBarrier.java`](../src/main/java/com/anchr/core/search/application/SegmentIndexWriteBarrier.java)

### 执行时序

| 阶段 | active model | read/write alias | 索引写入 |
|---|---|---|---|
| 选择前 | 旧模型 | 旧索引 | 可写 |
| 选择目标配置 | 仍是旧模型 | 旧索引 | 可写，等待管理员确认重建 |
| 重建中 | 仍是旧模型 | 旧索引 | JVM 写锁全程阻止写入 |
| alias 切换成功 | 随后切为新模型 | 新索引 | 激活完成后恢复写入 |
| 重建失败 | 旧模型 | 旧索引 | 释放锁后恢复写入 |

具体流程：

1. 设置页选择 Embedding/Multi Embedding 配置。
2. 如果 profile fingerprint 与 active 配置不同，后端只调用 `requestRebuild(targetProfile)`，不执行 `repository.select`，也不刷新 active client。
3. 管理员确认后，重建任务取得 JVM 独占写锁；普通索引写入使用读锁，因此会等待重建完成。
4. 重建按 `targetProfile.configId` 读取目标配置，即使该配置尚未 enabled，也可创建只供重建使用的 Session。
5. 创建新物理索引、scroll 旧索引并按 ANCHR-101B 的 Projection Policy 重新生成向量，校验文档数、维度和 fingerprint。
6. 原子切换 read/write alias，再启用目标配置并失效本实例的 embedding client 缓存。
7. 如果配置启用失败，立即把 alias 切回旧索引并按失败流程清理新索引。

待重建目标和进度只在内存中。实例重启后任务丢失，旧 active 配置不变；管理员重新发起即可。这里接受 alias 切换与数据库配置启用之间无法跨系统事务覆盖的极小崩溃窗口，不为此增加持久化状态机。

ES mapping `_meta` 只保留重建与运行校验需要的：

```text
profileVersion
profileFingerprint
embeddingCapability
embeddingModel
embeddingDimension
```

### 验收

- 选择不同 fingerprint 的配置后，`capability_config.enabled` 和 query embedding client 仍保持旧模型。
- 重建使用目标配置的精确 Session，不依赖目标配置提前 enabled。
- 同维度但 fingerprint 不同也必须重建。
- 重建持有 JVM 写锁的整个期间，Ingestion bulk、删除等索引写操作不可执行；查询仍可读旧 alias。
- alias 切换完成后才启用新配置；重建失败不改变旧 active 配置和旧 alias。
- 源码中不存在 `embedding_profile_deployment`、`physical_index_profile`、`embedding_index_write_lease` 及对应 Repository/Mapper/状态机。

### 实施与验证记录（2026-07-26）

- 已删除 V20 三张控制表及对应 migration、Repository、Mapper、deployment/lease/runtime snapshot/rollback API 代码。
- `SegmentIndexWriteBarrier` 已恢复为单实例 `ReentrantReadWriteLock`：普通索引写使用读锁，完整 rebuild 使用写锁。
- 设置页选择不同 profile 时只登记内存待重建目标；`ConfigDrivenEmbeddingAdapter` 可按目标 `configId` 打开尚未启用配置的 Session。
- alias 切换后由 `RetrievalCapabilityAcl → CapabilityServingConfigApi` 执行现有 `capability_config.select/disableAll` 并刷新本地缓存；激活异常会切回旧 alias。
- Web 已删除 deployment/impact/rollback 字段与在线迁移文案，明确显示“重建期间索引写入不可用”，待重建目标不会被误判成已经 active 的 profile mismatch。
- 启用中的 Embedding 配置若修改 `baseUrl/modelName/dimensions`，更新接口不再覆盖 active 行，而是返回一个新的禁用草稿；Web 使用返回的新 ID 走现有“选择目标配置 → pending rebuild → 确认重建”流程。只修改 API Key 时仍原地更新。
- Java 主源码编译通过；新增定向测试覆盖“不提前启用”和“未 enabled 的目标配置可供重建”。使用项目既定 Byte Buddy javaagent 完成全仓回归：517 项测试，0 failure、0 error，27 项因本机无 Docker 跳过；真实 Elasticsearch alias/停写窗口尚未验收。
- `anchr-web` 生产构建和 TypeScript 检查通过。

---

## ANCHR-102：建立正确的 HTTP 错误与上传清理契约

**目标：** 避免前端错误删除后端可能已经引用的 OSS 对象。

### 当前流程与根因

前端对任何 HTTP 4xx 删除已上传对象；后端却把所有 `BusinessException` 固定返回 HTTP 400，即使 `ApiError` 定义的是 404、409、500、503。错误协议也没有表达请求是否提交、能否清理和能否重试。

另一个不能忽略的边界是：`IngestionApplicationServiceImpl.createTask()` 在事务 `afterCommit` 中提交 worker，而 `ingestionTaskExecutor` 使用 `CallerRunsPolicy`。队列饱和时 worker 可能在响应线程同步执行；即使最终响应为 5xx，数据库中的 task/asset 也可能已经提交。因此 HTTP 状态本身，无论 4xx 还是 5xx，都不能作为 OSS 清理依据。

源码：

- [`anchr-web/src/lib/api-client.ts`](../../anchr-web/src/lib/api-client.ts)
- [`anchr-web/src/features/imports/imports-premium-page.tsx`](../../anchr-web/src/features/imports/imports-premium-page.tsx)
- [`GlobalExceptionHandler.java`](../src/main/java/com/anchr/core/common/exception/GlobalExceptionHandler.java)
- [`ApiError.java`](../src/main/java/com/anchr/core/common/exception/ApiError.java)

### 修复方案

后端：

1. 使用 `ApiError.code` 返回真实 HTTP status。
2. 4xx 使用 warn，5xx 使用 error。
3. 错误响应增加可选字段：

```json
{
  "errorCode": "INVALID_REQUEST",
  "traceId": "...",
  "retryable": false,
  "requestAccepted": false,
  "uploadCleanupAllowed": true
}
```

4. 只有确定事务未引用对象时才允许清理；未知异常、超时和 5xx 默认不允许。

字段语义使用三态而不是从 status 推断：

- `requestAccepted=false`：确定在持久化前拒绝或事务已回滚。
- `requestAccepted=true`：确定已持久化；本卡不据此实现恢复流程。
- 字段缺失：是否持久化未知。
- `uploadCleanupAllowed=true` 只能和“确定未产生持久引用”同时出现；`false` 或字段缺失都必须保留对象。

上传创建接口当前允许清理的后端白名单只有：

- Controller 执行前的 401/403 鉴权拒绝。
- JSON 绑定、枚举转换、Bean Validation 等请求校验失败。
- `INVALID_REQUEST`：创建事务抛出后整体回滚。
- `KNOWLEDGE_BASE_NOT_FOUND`：任何 asset/task 写入前失败。

其他 `BusinessException` 即使是 4xx 也不得自动获得清理许可；未知异常、响应写出失败、`afterCommit`/worker 同步执行异常一律 `requestAccepted` 未知且禁止清理。未来新增可清理错误时必须显式加入 endpoint 白名单，并证明没有已提交引用。

前端：

1. 扩展 `ApiError` 保存上述字段。
2. 移除“所有 4xx 都清理”的规则。
3. 仅 `uploadCleanupAllowed === true` 时删除 OSS。
4. 字段缺失时默认保留，兼容旧后端。
5. 保持现有 401/403 处理。

清理判断不再叠加 HTTP status 条件；status 负责表达错误类别，`uploadCleanupAllowed` 才是对象所有权处置信号。OSS 上传中途失败时，因为后端创建接口尚未调用，仍保留现有“只删除本批已上传对象”的安全补偿。

### 边界

本卡只定义跨业务通用的 HTTP 错误信封、status 映射和 OSS 清理许可。`IDEMPOTENCY_KEY_REUSED`、Docling 冲突、任务租约失效等 errorCode 的触发条件分别由 103、105、106 定义；本卡不得实现创建去重、业务重试或任务恢复。

### 发布与验收

先发布前端保守清理，再发布后端错误契约。

- `PROVIDER_UNAVAILABLE` 返回 503。
- `INGEST_RETRY_ONLY_FAILED` 返回 409。
- 参数校验错误可安全清理。
- 网络超时、502、503、响应体损坏不得删除 OSS。

Index 管理接口同样遵守该契约：空 `taskId` 返回 `INVALID_REQUEST/400`，retry/confirm/prepare 状态冲突返回 `INDEX_OPERATION_CONFLICT/409`，不再通过 `Result.error(String)` 产生 HTTP 200 或无稳定 errorCode 的响应。

---

## ANCHR-103：Ingestion 创建请求幂等与前端恢复

**目标：** 解决“任务已创建、前端未收到 taskId”造成的重复提交和孤儿任务。

### 根因

前端的 `import-upload:<uuid>` 只存在浏览器本地，没有发送给后端。后端事务可能已经提交，但响应丢失后前端无法找回任务。文件 hash 只解决资产去重，不解决创建请求幂等。

源码：

- [`anchr-web/src/features/imports/imports-premium-page.tsx`](../../anchr-web/src/features/imports/imports-premium-page.tsx)
- [`IngestionApplicationServiceImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionApplicationServiceImpl.java)
- [`IngestionTaskCreateRequestDTO.java`](../src/main/java/com/anchr/core/ingestion/interfaces/rest/dto/IngestionTaskCreateRequestDTO.java)

### 修复方案

#### 后端协议与数据模型

创建请求增加可选 `clientRequestId`。新前端始终生成并发送该字段；后端暂时允许旧客户端不传，缺失时保持原有“每次创建新任务”行为。ID 只允许 `[A-Za-z0-9._:-]+`，长度不超过 128。

后端先按真实写入语义规范化请求，再生成 `v1:<sha256>` 格式的 `requestHash`：

- 覆盖 `kbId`、生效后的 `sourceType`、生效后的 `dedupeStrategy`。
- 每个 item 覆盖生效后的 `fileName`，以及 `title/fileType/mimeType/sizeBytes/objectKey/fileHash`。
- 空白字符串和文件类型大小写规则与 Asset 创建逻辑保持一致。
- 保留 item 原始顺序，不排序。当前同批前一个 item 的 Asset 写入会影响后一个 item 的去重决策，换序不是等价请求。
- 不包含时间、STS 凭据等非业务输入；版本前缀用于未来安全升级规范化算法。

`ingestion_task` 增加：

```text
client_request_id varchar(128) collate utf8mb4_bin null
request_hash      varchar(80)  collate ascii_bin null
unique(created_by, client_request_id)
```

`NULL` 兼容旧请求；binary collation 保证请求 ID 和 hash 不被大小写折叠。

当前鉴权仍是单用户模式，所有 token 的领域 `created_by` 都是 `system`，因此该唯一键在当前部署中实际是全局作用域；UUID 客户端 ID 避免正常碰撞。未来引入真实用户主体后，必须先让 `created_by` 反映稳定用户 ID，再依赖该列实现多租户分区，不能误把 access token 本身写入领域所有者字段。

#### 并发与事务

行为契约：

- 首次请求：创建任务，201。
- 相同 ID + 相同 hash：返回原任务，200。
- 相同 ID + 不同 hash：409 `IDEMPOTENCY_KEY_REUSED`。
- 同一 ID 跨 KB 复用也返回 409；查询按当前用户和 KB 双重隔离。
- `IDEMPOTENCY_KEY_REUSED` 永远不授予 OSS 清理许可，因为 winner 可能已经引用同一批对象。
- 已提交请求的回放先于“KB 当前是否仍为 ACTIVE”的校验；恢复查询同样只按创建者、KB 和请求 ID 定位历史受理结果。这样 KB 在响应丢失后被归档时，前端仍能找回 winner，不会把已受理请求误报成可清理的 `KNOWLEDGE_BASE_NOT_FOUND`。

并发正确性不能依赖“先查再插”。实现以 `(created_by, client_request_id)` 唯一约束裁决 winner，并且只识别该约束产生的 `DuplicateKeyException`；其他主键或唯一键冲突原样抛出，不能伪装成幂等回放。

创建使用独立 `REQUIRES_NEW` 写事务。并发 loser 先让本次 Asset、TaskItem、Task、Activity 和统计写入全部回滚，之后再开启新的只读 `REQUIRES_NEW` 事务查询 winner。这样既不会遗留 loser Asset，也不会在 MySQL `REPEATABLE READ` 的旧快照中查询不到刚提交的 winner。只有 winner 的 after-commit 回调会提交 worker。

新增恢复接口：

```http
GET /api/v1/kbs/{kbId}/ingestion-tasks/by-client-request/{clientRequestId}
```

找到返回 200；不存在返回 404 `INGESTION_TASK_NOT_FOUND`。成功和业务 404 都显式 `Cache-Control: no-store`，前端请求也使用 `cache: no-store`，避免短暂 404 被缓存。

#### 前端恢复状态机

UPLOAD 创建链路统一使用同一协议：

1. 用户每次主动发起新导入时生成新的 UUID，并建立 `import-create:<clientRequestId>` placeholder。
2. POST 前同步持久化完整、可重放且不含 STS 凭据的请求体，并持久化最终 `objectKey/fileHash`。
3. 状态区分 `UPLOADING -> SUBMITTING -> CONFIRMING -> RESOLVED/CREATE_REJECTED`。`SUBMITTING/CONFIRMING` 不展示虚假的 `0/N` 处理进度。
4. 页面刷新或跨标签页接管时，先按 clientRequestId 查询；只有精确收到 404 `INGESTION_TASK_NOT_FOUND` 才用原 ID、原 body 重放 POST。未知 404、网络错误、响应损坏、408/429/5xx 和未明确处置的 4xx 都继续 `CONFIRMING`，不得生成新 ID。
5. owner lease 防止另一个标签页把仍在上传/提交的请求误判为中断；到期后才接管确认。持久化容量限制不得淘汰未决任务。
6. 找回真实 taskId 后同步替换 placeholder，并保留 `navigationPending` 直到 URL 已包含 taskId；即使任务因全量 SKIP 瞬间终态、且页面在路由更新前崩溃，刷新仍能恢复导航。
7. 显式拒绝只有在 102 契约返回 `uploadCleanupAllowed=true` 时才清理 OSS；幂等冲突即使错误元数据异常也被前端二次禁止清理。
8. 跨标签页合并以 taskId 和终态为单调事实，消费 `StorageEvent.newValue` 并在旧快照覆盖后修复性回写；任务序列化使用稳定次序，避免标签页之间反复改写。
9. 显式拒绝必须先同步持久化 `CREATE_REJECTED`，成功后才执行尽力而为的 OSS 清理；恢复轮询中的多个未决请求逐项结算，单个慢请求不阻塞其他请求落终态。

前端恢复按 access-token identity 分区保存，不持久化 AccessKey、SecurityToken 或 OSS 客户端实例。任务已解析到 taskId 后清除不再需要的重放请求内容，只保留任务关联和待导航状态。

### 边界

本卡生命周期截止到“唯一 ingestion task 已创建或可按 clientRequestId 找回”。任务创建后的 stage 推进、进程重启恢复和 worker lease 归 106；Docling requestId/jobId 归 105；ES segment 幂等归 107。不得用 `clientRequestId` 兼任 parse attempt、execution epoch 或 index generation。

### 验收

- 响应丢失和页面刷新后可以恢复任务。
- 并发提交只产生一个 task。
- 不同 payload 复用 ID 返回 409。
- 用户主动重新上传生成新 clientRequestId。

### 发布顺序与验证记录

幂等字段和唯一约束已经直接并入 V1 建表基线，不再存在独立 V8。当前迁移集只支持新建或明确重建的数据库；完成数据库初始化后按 `anchr-app -> anchr-web` 发布，旧前端不传 ID 时新后端仍兼容，新前端依赖恢复查询接口，不能先于后端发布。

本地验证：

- `anchr-app` compile、test-compile 和全量测试通过：307 项，0 failure，0 error，15 skipped。
- 15 项均为当前环境无 Docker 而跳过的 Testcontainers 测试；其中新增的真实 MySQL migration/双线程唯一键竞争测试已编写，但尚未在本机实际执行。上线前必须在有 Docker 或真实 MySQL 的 CI 执行，确认实际驱动异常链包含约束名 `uk_ingestion_task_creator_request`，并验证 loser Asset 回滚。
- 后端定向测试覆盖 legacy、同请求回放、payload/KB/item 顺序冲突、目标与非目标 DuplicateKey、201/200、no-store、`REQUIRES_NEW`、KB 归档后的受理恢复和 OSS 清理禁令。
- 前端定向测试覆盖精确 404 后原请求重放、lookup 错误元数据不得代表历史 POST、未知错误持续确认、冲突禁止清理、持久化字段白名单、owner lease、未决任务不淘汰、placeholder 丢失后的同步 upsert、终态任务待导航恢复、跨 KB 隔离、稳定存储排序和旧快照修复。
- `anchr-web` 全部 74 项 Node 测试通过；`tsc --noEmit --incremental false`、本卡改动文件 ESLint、production build 和两个仓库的 `git diff --check` 均通过。Node 直接加载 TypeScript 测试模块时仅有既有 `MODULE_TYPELESS_PACKAGE_JSON` 性能提示。

---

## ANCHR-104：关闭未消费且加密错误的内嵌图片上传链路

**目标：** 停止传输无消费者的 STS 凭据，不影响文本解析和独立图片 OCR。

### 根因

后端使用配置 IV 加密，却发送另一个随机 IV；Docling 按请求 IV 解密，必然失败。Docling 将失败降级为 warning，后端又不消费 `images` 和 `warnings`，因此当前只有凭据暴露和噪声，没有产品功能。

源码：

- [`AesUtil.java`](../src/main/java/com/anchr/core/common/util/AesUtil.java)
- [`IngestionTaskProcessorImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionTaskProcessorImpl.java)
- [`anchr-docling/src/anchr_docling/images.py`](../../anchr-docling/src/anchr_docling/images.py)
- [`anchr-docling/src/anchr_docling/schemas.py`](../../anchr-docling/src/anchr_docling/schemas.py)

### 修复方案

1. 增加 `app.docling.embedded-image-upload-enabled=false`，默认关闭。
2. 关闭时 `ParseRequest.oss=null`，不获取和传输 STS。
3. Docling 增加 `includeEmbeddedImages=false`；关闭时保留 caption/alt 文本，但不上传和告警。
4. 独立 IMAGE 文件继续根据 `objectKey` 生成短期签名下载地址，并通过 Docling 请求中的内部 `sourceUrl` 传输给 OCR。
5. 删除或明确弃用未实现的 `EncryptedCredentials.tag`。

真正启用归 ANCHR-110：使用 AES-GCM/ChaCha20-Poly1305，携带 version、keyId、nonce、tag、expiration，以 AAD 绑定 requestId、bucket、basePath、endpoint，并增加上传目标 allowlist、稳定图片制品契约和后端消费者。110 完成全链路验收前，本卡门禁必须保持默认关闭。

### 边界

本卡只关闭无消费者的“文档内嵌图片上传”支线并保持独立 IMAGE OCR。它不修改独立图片的 OCR/embedding 流程，不改变 Docling job 指纹和重试协议，也不实现 AEAD、图片消费者或查询链路；这些重新启用条件唯一归 ANCHR-110。

### 验收

- 文本 chunk 输出不变。
- app → Docling 请求不再包含 STS。
- 不再产生无意义的 `image_upload_failed`。
- 独立图片 OCR 和 ANCHR-101A/101B 正常。

---

## ANCHR-105：重构 Docling attempt 与幂等协议

> 2026-07-28 边界修正：保留 Docling 进程内的 `requestId + stable fingerprint` 幂等协议；App 不再持久化 parse attempt、requestId、jobId、sourceRevision 或请求快照。下文涉及这些 App 持久字段及跨重启恢复的内容属于旧方案，由 ANCHR-106B 的最终决策替代。

**目标：** 区分网络重试和业务重试，消除同一 item 重试的 409 冲突。

### 根因

app 固定使用 `taskId:itemId`；Docling 对完整请求 JSON 做 SHA-256。签名 URL 等瞬态字段每次不同，因此结果 TTL 内重试会冲突。

源码：

- [`IngestionTaskProcessorImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionTaskProcessorImpl.java)
- [`DoclingClient.java`](../src/main/java/com/anchr/core/integration/ai/client/DoclingClient.java)
- [`anchr-docling/src/anchr_docling/jobs.py`](../../anchr-docling/src/anchr_docling/jobs.py)

### 修复方案

item 的 Parse 协议状态增加：

```text
parse_attempt default 1
docling_request_id
docling_job_id
source_revision
```

requestId 改为 `taskId:itemId:parseAttempt`：网络重试和 Docling 404 重提不增加 parse attempt；用户显式重新解析或确定性失败后的业务重试才增加。

Docling 请求增加 `contractVersion=2` 和 `sourceRevision`，并要求 v2 的 `fileName` 非空；旧协议仍允许缺省 `fileName` 并从 URL path 推断类型。v2 指纹只包含 requestId、sourceRevision、fileName、parse options、contractVersion 和稳定输出配置，不包含 sourceUrl 签名、STS 密文或过期时间。旧请求继续使用旧指纹，保证滚动升级。

Docling 是有界内存边车，`requestId -> fingerprint/job` 冲突记录只在对应内存任务存续期间有效；ACK、TTL、容量淘汰或进程重启后允许用同一 attempt 身份重新创建 job，本卡不为边车增加持久化 fingerprint ledger。App 数据库中的 `parseAttempt/requestId/sourceRevision` 是跨边车生命周期的身份事实源，记录消失后的可靠恢复和 stale worker fencing 由 106 消费这些字段完成。

HTTP 分类：

- 408、425、429、5xx：瞬态；
- 404：当前 parse attempt 可按相同 requestId 重提；
- 409：协议冲突，不得循环；
- 401：配置错误，不得重试。

app client 同时交付非阻塞的 `submitJob/getJob/ackJob` 协议方法；为滚动迁移可以暂时保留旧 `parse()` facade，但 106 上线后必须删除其长轮询职责。发布顺序：Docling 先支持双协议，app 再发送 v2。

### 边界

本卡只定义一次 Parse 业务尝试在 app 与 Docling 之间的持久身份、内存记录存续期间的指纹冲突语义、HTTP 分类和 submit/get/ack 幂等语义。何时调度这些调用、边车记录消失后的恢复、worker lease、`PARSE_WAIT` 轮询节奏、所有业务状态写入的 stale worker fencing 和 artifact 持久化归 106；本卡不新增通用调度器。`parse_attempt` 不能与 106 的 `execution_epoch/stage_attempt` 合并。

### 验收

- 对应内存记录存续期间，同 parse attempt 重复提交返回同一 job。
- 对应内存记录存续期间，仅签名 URL 变化不冲突。
- 对应内存记录存续期间，sourceRevision 改变但复用 requestId 必须 409。
- 显式重新解析生成新 parseAttempt/requestId/job。
- ACK、TTL、容量淘汰或 Docling 重启导致 job 404 时，App 必须使用数据库中同一 parse attempt 的 requestId、sourceRevision 和稳定参数重提；新 job 的恢复调度与 stale worker fencing 由 106 验收。

### 实现与验证记录

- `anchr-app` 已在 V1 建表基线直接定义 `parse_attempt/docling_request_id/docling_job_id/source_revision`；首次可解析 item 写入 attempt 1 和 `taskId:itemId:1`。
- `sourceRevision` 优先使用文件 hash，其次使用 objectKey、assetId，并以 `v1:<sha256>` 落库。每次重新生成的 OSS 签名 URL 不参与 revision。
- 单项重试和批量失败重试由应用层显式计算 `expectedAttempt/nextAttempt/nextRequestId`，再以 `status=FAILED + expectedAttempt` 做 CAS 更新并清空旧 jobId，不再依赖多表 `UPDATE` 的 `SET` 求值顺序。批量最多 50 项并处于同一事务，任一 CAS 失败则整批回滚。jobId 回写以 requestId 为 fence；旧 worker 对业务状态的完整 fencing 归 106。
- `DoclingClient` 已提供单次、非阻塞语义的 `submitJob/getJob/ackJob`，并明确分类 408/425/429/5xx、404、409、401 和其他永久错误。现有 Processor 暂时继续使用由这三个方法组成的同步 `parse()` facade，长轮询移除仍归 106。
- `anchr-docling` 的 v2 指纹只覆盖 requestId、sourceRevision、fileName、解析选项和稳定输出位置；排除 sourceUrl 签名、STS 密文和过期信息。缺少 `contractVersion` 的旧请求继续使用完整 JSON 指纹。
- `anchr-docling` 对 v2 强制校验 `fileName` 非空并规范化首尾空白，缺失或纯空白返回 422；显式 v1 和未带版本的 legacy 请求仍允许缺失，不改变旧协议行为。
- Docling ACK 已幂等：终态结果已经确认、TTL 过期或重启丢失时重复 DELETE 仍返回 204；运行中 job 仍返回 409。
- 发布顺序为 `anchr-docling 双协议版本 -> 使用合并后的迁移基线初始化数据库 -> anchr-app v2 请求`，不能让 App 先发送 v2。
- `anchr-docling` 全量 20 项测试和 10 个 subtests 通过；改动文件 Ruff 通过。全仓 Ruff 当前有 15 个既有问题：核心 `src` 9 个、`scripts` 6 个。
- `anchr-app` compile、test-compile 和全量测试通过：327 项，0 failure，0 error，16 skipped。16 项均为当前环境无 Docker 而跳过的 Testcontainers 测试；其中建表基线字段、显式 retry CAS 和旧 job fencing 的真实 MySQL 测试已编写但未在本机执行，上线前必须在有 Docker/真实 MySQL 的 CI 验收。

---

## ANCHR-106：Ingestion 改为数据库驱动的可恢复状态机

> 2026-07-28 边界修正：已确认 `anchr-app` 单实例运行，Docling 单 worker 串行处理，且失败不能断点续跑。本卡原 lease、fence、分阶段跨重启恢复方案停止采用；当前实现和验收以 ANCHR-106B 为准。

**目标：** 任务可靠性不依赖 JVM 内存、afterCommit 回调或浏览器轮询。

### 根因

- afterCommit 直接提交本地线程池。
- JVM `ReentrantLock` 不能支持多实例。
- 状态更新没有 attempt、lease 或前置状态条件。
- Spring 线程可同步轮询 Docling 45 分钟。
- `CallerRunsPolicy` 可能把长任务压到请求/事务回调线程。
- app 重启后没有通用机制领取 PENDING 或陈旧 RUNNING item。

源码：

- [`IngestionApplicationServiceImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionApplicationServiceImpl.java)
- [`IngestionTaskProcessorImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionTaskProcessorImpl.java)
- [`ThreadPoolConfig.java`](../src/main/java/com/anchr/core/common/config/ThreadPoolConfig.java)
- [`anchr-docling/src/anchr_docling/main.py`](../../anchr-docling/src/anchr_docling/main.py)

### 修复方案

V1 建表基线直接定义以下兼容执行字段：

```text
execution_stage
execution_epoch
claim_version
stage_retry_count
stage_started_at
next_action_at
lease_token
lease_until
parse_request_snapshot
```

`docling_request_id/docling_job_id/source_revision/parse_attempt` 直接消费 ANCHR-105 的字段和语义，本卡不得重复定义或改名。

保留现有 `stage/status/progress/errorCode/errorMessage` 作为前端投影，内部状态机为：

```text
PARSE_SUBMIT → PARSE_WAIT → EMBED → INDEX → COMPLETE
      │              │         │       │
      └──────────────┴─────────┴───────┴→ FAILED
```

实现中不存储虚构的 `RETRY_WAIT` stage。一次可重试失败会保留当前 `execution_stage`，写入未来的 `next_action_at` 并释放 lease；到期后重新 claim 同一 stage。跨 stage 成功迁移时 `stage_retry_count` 清零。

定时调度器每轮只扫描数据库中到期且无有效 lease 的 item。候选先提交到有界 executor，worker 再在短事务中用 `SELECT ... FOR UPDATE SKIP LOCKED` claim；因此 executor `AbortPolicy` 拒绝时数据库尚未领取，下一轮仍可恢复。`afterCommit submit()` 只保留为低延迟 wake-up hint，不再承担可靠性。

claim 使用数据库时间生成 `lease_until`，并原子执行：

- `claim_version + 1`；
- 生成新的随机 `lease_token`；
- 首次进入 stage 时固定 `stage_started_at`；
- PENDING 投影为 RUNNING，并刷新 task summary；
- 接管过期 lease 时增加 `stage_retry_count`。

所有迁移都携带 `itemId + taskId + kbId + executionEpoch + expectedExecutionStage + expectedClaimVersion + leaseToken + RUNNING` fence，并完整写入下一状态。lease 到期本身不让当前 worker 立即失效；只有新 worker 接管并改变 token/version 后，旧 worker 才无法提交。这避免了“外部调用刚返回、仅因时钟越过 lease 就丢结果”的窗口，同时仍能阻止 stale worker 覆盖。

本地 `locallyDispatchedItems` 只去重“尚未开始 DB claim”的 executor 提交；claim 完成即释放。即使旧线程卡在 provider 调用，同一 JVM 也能在 lease 过期后再次 dispatch 和接管。

旧的无 fence 写接口 `prepareParseAttempt/recordDoclingJob/markItemRunning/markItemSuccess/markItemFailed` 已从 Repository、Mapper 和 XML 删除，运行链路不能再绕过 item fence。显式从 `EMBED/INDEX` 创建内部任务时必须具有可恢复所需的 Docling job identity，禁止构造无法恢复的非法起点。

#### 重试预算

`stage_retry_count` 是当前内部 stage 的统一恢复预算：

- Docling/OSS/embedding 的临时失败、ACK 临时失败、job 404、可重试 terminal job 和过期 lease 接管会增加；
- `PARSE_WAIT` 的 queued/running 正常轮询不增加；
- 404、ACK 后重提和内部恢复不增加 `parse_attempt`，仍复用同一业务解析身份；
- 成功进入下一内部 stage 后清零；
- 默认 `stage-max-retries=5` 表示最多 5 次恢复，随后 fenced FAILED；
- `embedding-rate-limit-max-attempts` 保持“provider 总调用次数”语义，值为 N 时只允许 N-1 次持久化重试，不能多出第 N+1 次调用；429 优先读取 `OpenAiException.statusCode`，消息匹配只作为兼容兜底。

用户显式 retry 是新的业务执行：`execution_epoch + 1`，同时按 ANCHR-105 将 `parse_attempt + 1`、生成新的 `docling_request_id`，并清空旧 snapshot、job、lease 和 target generation。内部恢复只增加 `claim_version/stage_retry_count`，不能冒充新的 parse attempt。

#### Parse 调度与恢复

消费 ANCHR-105 交付的 `submitJob/getJob/ackJob`，移除 Processor 对旧 `DoclingClient.parse()` 长轮询 facade 的依赖，不在 Java 线程中 sleep/poll。成功结果继续保留在 Docling 终态 job 中，直到 INDEX 完成后 ACK。

一次 worker 只执行一次 submit/get 或一个本地阶段；queued/running 写入下一次轮询时间后立即释放线程。`parse-stage-timeout` 以首次进入当前等待阶段的 `stage_started_at` 计算，正常轮询和同 stage retry 不会重置。

首次 submit 前持久化 secret-free `parse_request_snapshot`，包含 contractVersion、fileName、options 和稳定 OSS 目标，但不包含签名 URL、STS 密文或过期时间。每次调用时重新生成：

- 有 objectKey：使用当前签名下载 URL；
- 可解析资产必须具有 objectKey；缺失时按数据不完整失败，不再回退用户来源 URL；
- 旧的 embedded-image OSS 凭据仍受 ANCHR-104 默认关闭门禁保护，不能因 106 被重新启用。

当 ACK、TTL、容量淘汰或边车重启使 Docling 内存幂等记录消失时，状态机必须从数据库读取并复用 ANCHR-105 已持久化的 `parse_attempt/docling_request_id/source_revision` 和稳定解析参数重提；不得在恢复过程中静默生成新 parse attempt 或改变 sourceRevision。

可重试 terminal job 会先 ACK，再以同一 v2 身份返回 `PARSE_SUBMIT`；ACK 失败则保留原 jobId 和身份，在同 stage 重试。`anchr-docling` 已增加成功和失败终态的合同测试，确认 DELETE 同时清除 request 映射，此后相同 `requestId + fingerprint` 会创建不同的新 jobId。

#### Docling 终态结果与内存向量交接

Parse 成功后直接 fenced 迁移到 `EMBED`，不复制 OSS Parse artifact，也不提前 ACK。App 在 EMBED/INDEX 按 item 中已持久化的 job identity 读取成功结果。

EMBED 在内存中生成 chunk 向量。正常成功路径以 fenced `EMBED → INDEX` 迁移保留当前 lease，随后在同一 worker 中把 chunks 直接交给 `IngestionIndexFinalizer`。若进程在交接后退出，恢复 worker 从 Docling 成功 job 重新映射并嵌入；若 job 已因重启或 TTL 丢失，则回到 `PARSE_SUBMIT` 整文档重跑。成功 INDEX 后才 best-effort ACK。

同一 Asset 的跨 task generation、目标 generation 清理重写和 ES 可见性不由 Docling 结果或内存交接冒充解决，仍归 ANCHR-107。

#### MySQL 原子边界

普通 stage 迁移和 Asset parse/index 投影通过 `IngestionStageTransactionCoordinator` 放在同一个短 MySQL 事务；任一写入异常时 item、task summary 和 Asset 一起回滚。INDEX 仍沿用现有 `IngestionIndexFinalizer`：先锁 item claim，再锁 Asset，并在事务内调用 ES bulk。该做法能 fence stale item worker，但不能让 ES 随 MySQL 回滚，明确留给 107 的 generation/outbox 修复。

### 边界

本卡拥有任务从到期领取到各 stage 完成的持久化调度、fencing 和恢复；不拥有创建请求去重（103）、Parse 协议幂等（105）、ES generation/可见性（107）或 physical index alias（101C）。进入 `INDEX` 时只把内存 chunks、Docling job 恢复入口、execution epoch 和 lease context 交给 107，不自行定义 segmentId 或激活 generation。

以下仍是 ANCHR-107 的未解决窗口，不能把 106 标成跨存储一致性完成：

- ES bulk 仍发生在 MySQL 事务/行锁期间，ES 成功后 MySQL 回滚无法撤销；
- 不同 task 同时处理同一 Asset 时还没有 Asset generation fence，旧 task 可能覆盖新 task 的 Asset 状态；
- mapper 的普通 segmentId 在 INDEX 恢复重算时可能变化，恢复前必须清理同一未激活 target generation，不能直接追加；
- overwrite cleanup 仍是 COMPLETE 后 best-effort 删除，进程退出可能跳过；
- partial bulk、active/target generation、目标 generation 清理重写、激活门禁和清理 outbox 全部归 107。

Docling `images[]` 由 App 在成功 job 存续期间直接消费；`DOCUMENT_IMAGE` Segment、内嵌图片对象生命周期、图片召回和 Preview 不在 106 展开。前端不读取内部 execution/lease 字段。

### 兼容与验收

前端继续按 taskId 轮询，现有 stage/progress/errorCode 不变，浏览器不参与任务推进。

- `anchr-web` 无生产代码改动：现有轮询只消费公开 task/item 投影，内部状态增加不改变 DTO。
- `anchr-docling` 无生产代码改动：直接消费 105 的单次 submit/get/ack；本卡只补 ACK 后同身份重提合同测试。
- REPARSE/REEMBED 继续保持旧 Processor 的真实行为：都从源文件重新 Parse；fresh REEMBED 不允许误从内部 EMBED 起步。
- 图片文本模型继续允许无 OCR chunk 不写向量；多模态图片仍只把原图向量放到第一个 carrier chunk，不因状态机改造改变 101A 的临时兼容语义。
- app 可在 PARSE_WAIT、EMBED、INDEX 重启恢复；executor 拒绝、线程中断或 provider 卡住不遗失数据库任务。
- 多实例/同 JVM接管后，stale worker 无法覆盖新 stage attempt 或 execution epoch。
- Docling 记录在 ACK、TTL、容量淘汰或重启后丢失时，恢复仍复用同一 parse attempt 的稳定身份。

### 发布顺序

106 不能让旧 Processor 与新 Scheduler 滚动混跑，因为旧版本没有 execution fence：

1. 确认已部署 ANCHR-105 双协议 Docling；
2. 停止/排空所有旧 app ingestion worker；
3. 使用合并后的 Flyway 基线初始化或明确重建数据库；
4. 部署并启动新 app scheduler；
5. 验证 claim backlog、过期 lease、stage retry、Docling job 保留/ACK、EMBED→INDEX 租约交接和 Docling queue 指标后再恢复正常流量。

当前合并迁移只支持人工重建后的 fresh database，不迁移旧 normalized schema 或执行历史。当前平台仍须遵守既有的“同一套 object storage 配置承载所有 Asset”约束；运行中的 storage endpoint/bucket 切换会让原文件和当前 parse attempt 图片目录不可读。

### 实施与验证记录

- `anchr-app`：合并后的迁移基线、两表 DB claim/lease/fence、阶段调度器、Docling 终态结果读取、内存向量交接、INDEX 恢复时重算、原子 Asset 投影、AbortPolicy、无 fence 旧接口删除均已实现。
- 单元/合同测试覆盖 executor 拒绝、同 JVM lease 接管、正常轮询不耗重试、404 恢复、稳定 snapshot、终态 ACK、正常链路单次 embedding、INDEX 恢复重算、interrupt、objectKey 签名下载地址、图片兼容、非法 stage 起点和事务 rollback。
- MySQL Testcontainers 测试已编写：DB-time lease、过期接管、stale token、显式 retry epoch reset、item/task summary 在 Asset 投影异常时回滚；本机无 Docker，相关用例会跳过，必须由有 Docker 或真实 MySQL 的 CI 执行。
- Parse artifact 专用 OSS create-only/read/digest 能力已删除；真实 OSS 仍只需验证原文件签名、图片签名和受控前缀删除。
- `DoclingClient` 的成功响应也改为有界流读取，`app.docling.max-response-bytes` 默认 256 MiB；错误响应只读取前 4 KiB，防止边车异常响应先完整进入 JVM 堆。
- 106 实施轮当时的本地回归：`anchr-app` 全量 408 项，0 failure、0 error、23 skipped；23 项均因当前环境无 Docker 而跳过的 Testcontainers 测试。`anchr-docling` 全量 20 项及 10 个 subtests 通过，改动文件 Ruff `--no-cache` 通过。`anchr-web` ingestion/background recovery 相关 23 项通过；106B 的最新 app 结果单独记录在下节。
- 本卡“已完成”表示源码实现和可执行的本地验证完成，不表示已提交、执行数据库初始化或发布；也不表示 ANCHR-107 已完成。

---

## ANCHR-106B：Ingestion 持久化收敛为两表

**目标：** 删除没有实际恢复能力的 execution、parse-attempt、artifact 和 lease 模型，只保留任务业务事实与公开执行状态。

> 2026-07-28 最终决策：`anchr-app` 单实例运行；一个 worker 串行执行一个文档的 Parse → Embed → Index。服务重启后残留 `RUNNING` item 直接失败，由用户人工重试整份文档，不做断点恢复。本节替代 ANCHR-105/106 中所有 App 端 attempt、snapshot、lease、fence 和跨重启恢复设计。

### 业务结论

- Docling 当前单 worker 串行执行，失败后不能从文档中间续跑；所谓 execution/parse-attempt 历史不能提供断点续传。
- `reembed` 没有前端调用方，而且现有实现仍从原文件重新解析；ES 索引重建直接读取旧索引中的 Segment，不读取 Parse artifact。
- 因此 Parse artifact 只是在 App 内部复制一次 Docling 结果，并不承载独立业务事实。删除它后不影响上传、重试、reparse、generation 切换或索引重建。

### 最终数据结构

只保留：

1. `ingestion_task`：任务身份、来源、幂等指纹、统一去重策略、汇总状态和审计时间。
2. `ingestion_task_item`：资产身份、目标 generation、公开阶段、状态、进度、去重结果和错误。

明确删除：

```text
ingestion_item_execution
ingestion_item_parse_attempt
ingestion_item_artifact
```

`ingestion_task_item` 固定为 16 个持久字段：

```text
id / task_id / asset_id / target_index_generation
file_name / file_hash
stage / status / progress
dedupe_result / duplicate_asset_id
error_code / error_message
created_at / updated_at / finished_at
```

`kb_id`、`created_by` 和 `dedupe_strategy` 只保存在 task，查询 item 时通过 task join 得到上下文。Docling requestId、jobId、sourceRevision、请求模板和临时凭据只存在于当前 worker 内存；不写数据库。公开 DTO 不增加内部字段。

### 调度、恢复和重试

执行模型收敛为：

```text
PENDING --原子领取--> RUNNING/PARSE → EMBED → INDEX → SUCCESS
                              └──────────────→ FAILED
```

- 调度器只扫描 `PENDING`，数据库用 `status=PENDING` 条件原子改为 `RUNNING/PARSE`，避免同一实例重复领取。
- ingestion executor 固定单线程；领取后同一 worker 内同步完成 Docling submit/poll、Chunk 映射、Embedding 和 Index，不释放成多个可恢复阶段。
- Docling 的瞬态失败和 job 丢失只在当前 worker 内按有限次数重提；服务重启不恢复 job。
- App 启动时把残留 `RUNNING` item 和对应 Asset 状态置为 `FAILED`，错误信息明确要求重新执行文档。
- 用户显式 retry 复用同一 item，分配严格递增的新 `target_index_generation`，随后从源文件完整重跑。
- 成功索引后 best-effort ACK Docling；ACK 失败交给 Docling TTL，不倒退已完成业务状态。

### 图片对象生命周期

Parse artifact 删除后，图片对象不再依赖 registry 清单：

- 图片目录由现有业务身份确定：`storagePrefix/ingestion/assets/{assetId}/generations/{generation}/images/`。
- 不保存图片 manifest、attempt snapshot，也不新增图片专用 Outbox 类型。
- 失败的未激活 generation 复用 `DELETE_ASSET_GENERATION`；该事件同时删除确定性图片前缀和对应 ES Segment。
- Asset 删除枚举该 Asset 已使用的 target generation，逐一删除确定性图片前缀，再删除 ES Segment；安全路径校验保持不变。

### 迁移和兼容边界

- 合并后的 `V3__create_ingestion_tables.sql` 直接创建最终两表，不把全部 ingestion DDL 塞回其他业务迁移。
- 按已确认决策不迁移历史数据、不兼容 V13/V14/V15/V18 的旧表结构；发布前由人工停服务并重建数据库。
- 不新增依赖，不修改 ES Mapping、Docling 请求/响应、Web DTO 或外部组件。
- 删除 Parse artifact 专用 OSS create-only/read/digest API 和配置；保留原文件签名、图片 embedding 签名及安全目录删除。

### 验收

- fresh migration 只产生 `ingestion_task`、`ingestion_task_item`，不存在三张辅助表和 artifact 字段。
- `ingestion_task_item` 真实列数为 16，不包含 attempt、job、snapshot、execution、retry、lease 字段。
- 单线程 worker 在一次运行中完成 Parse → Embed → Index；同一 item 不会并行执行。
- 服务重启后残留 `RUNNING` item 变为 `FAILED`，不会自动续跑或读取 Docling 旧结果。
- 显式 retry 使用新 generation 完整重跑；旧 generation 的延迟清理不会误删新 generation 图片。
- 失败、Asset 删除和 generation 退休只删除自己拥有的确定性图片目录，不删除相邻前缀。
- 公开 task/item DTO、上传、reparse、独立图片 OCR、文档图片 Segment、Preview 和 Search 行为不回退。

### 实施状态

- 两表 DDL、Mapper、Repository、PENDING 原子领取和显式 retry 主链已完成。
- `PARSE_PERSIST`、Parse artifact store/config/model、artifact registry、三表 persistence record 和专用 OSS 读写能力已删除。
- attempt/job/snapshot/execution/lease 持久字段及相关恢复代码已删除；请求模板仅为单次 worker 内存值。
- Processor 改为单 worker 整链执行；启动恢复改为明确失败，图片清理由 Asset generation 事件统一负责。
- 旧 normalized schema 与恢复机制测试已删除，并由 16 列 DDL、原子领取、整链处理、重启失败和 generation 清理测试替代。
- 当前 `anchr-app` 全量测试 479 项，0 failure、0 error、30 skipped；跳过项为本机无 Docker 的 Testcontainers 用例，其中两表/16 列真实 MySQL 合同需在 CI 或重建后的真实 MySQL 补验。
- 本卡完成仅表示源码与自动化验证完成；人工数据库重建、真实 MySQL/OSS/Docling/ES 灰度和生产发布仍需单独执行。

## ANCHR-107：建立 Asset Segment generation 与 ES 写入幂等一致性

**状态：** 源码与本地回归已完成；业务库 V19、真实 Elasticsearch 故障演练和部署验收待完成。107 不得绕过 106B 的 V18 业务库迁移门禁进入生产。

**目标：** 解决 ES 已写入但 MySQL 回滚、部分 bulk 成功后重跑追加、overwrite 绕过 outbox。

### 根因

Mapper 每次生成普通 segmentId；ES bulk 发生在 MySQL 事务中但不能随事务回滚。若重试前不清理同一未激活 target generation，前一次部分成功记录会残留并形成追加。普通删除已有 outbox 和 row lock，但 overwrite cleanup 直接删 ES 并吞异常。

源码：

- [`DoclingChunkMapper.java`](../src/main/java/com/anchr/core/ingestion/infrastructure/parser/DoclingChunkMapper.java)
- [`SegmentBulkWriter.java`](../src/main/java/com/anchr/core/ingestion/infrastructure/persistence/es/SegmentBulkWriter.java)
- [`IngestionIndexFinalizer.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionIndexFinalizer.java)
- [`OutboxEventProcessor.java`](../src/main/java/com/anchr/core/kb/application/impl/OutboxEventProcessor.java)

### 修复方案

asset/item/ES segment 增加：

```text
active_index_generation
target_index_generation
index_generation
```

旧资产和旧文档视为 generation 0；reparse/reembed 分配新 generation；同一目标 generation 的所有写入重试始终复用该 generation。

Segment ID 保持普通 ID：

```text
segmentId = IdGen.nextIdStr()
ES _id = segmentId
```

`assetId + indexGeneration + segmentType + chunkOrder` 均已作为 ES 独立字段存在，只在需要匹配逻辑 Segment 时组合判断，不拼入 `segmentId/_id`，也不新增逻辑键字段。Docling `chunkId` 只用于解析 `chunkOrder`，不直接持久化或拼入 ID。

可见性流程：

1. 删除同一 Asset 下未激活 target generation 的重试残留。
2. 使用本次生成的普通 Segment ID 写入 target generation。
3. MySQL 原子切换 active generation。
4. 搜索召回后批量加载资产 active generation，过滤不匹配 hit。
5. 通过 outbox 删除旧 generation。

因此 ES 写完但 DB 未提交时新文档不可见；旧文档删除失败也不会再参与搜索。
如果 INDEX 在 partial bulk 后终态失败，失败事务会锁定当前 Asset；只要 target generation
为正且不等于 active generation，就在同一事务写入 `DELETE_ASSET_GENERATION`。Finalizer
发现 `target < active` 时同样失败任务并清理被淘汰的 target；`target == active` 时只失败
任务，禁止删除正在服务的 generation。Asset 已删除时不重复写 generation 事件，由既有
`DELETE_ASSET` 完成全量清理。

overwrite 必须在同一事务 soft delete 旧 asset 并写 `DELETE_ASSET` outbox，不再直接调用 ES 或吞异常。复用现有 outbox 的 claim、lease、backoff 和 `SKIP LOCKED`。

### 边界

本卡中的 `indexGeneration` 是单个 Asset 内容/segment 的逻辑版本，不是 ES 物理索引版本。本卡拥有目标 generation 清理重写、generation 激活门禁和 MySQL/ES 可见性；可以在所有召回路由之后统一过滤非 active generation，但不得改变路由分数或相对顺序。不创建/切换 read-write alias，不选择 embedding profile，不增加第二向量检索路由，也不搬迁 Outbox 模块。旧 generation 和 Asset 的外部清理由既有 Outbox 可靠投递机制负责。

### 验收

- bulk 部分失败后重试不产生重复 segment。
- ES 成功、DB 失败时新 generation 不可见。
- ES 写完后 app 崩溃可幂等恢复。
- overwrite 删除失败进入 outbox。
- 删除与 ingestion 并发不会复活资产。
- generation/Asset 清理事件失败后可按既有 Outbox 策略重试。
- INDEX 终态失败和被更高 generation 淘汰的未激活 target 会进入清理 Outbox；非 INDEX
  失败、active target 和已删除 Asset 不产生额外 generation 删除事件。

### 实施与验证记录

- V19 只增加 `asset.active_index_generation`、`ingestion_task_item.target_index_generation` 及查询索引；没有新增变化日志表、业务 CHECK 或外键。旧 Asset/旧 ES 文档兼容为 generation 0。
- 新建 Asset 固定从 generation 1 开始；REPARSE/REEMBED 在 Asset 行锁内按 `max(active generation, 已分配 target generation) + 1` 分配，旧数据中 target 为空的 item 在首次 claim 时用相同规则补齐。target 只保存在稳定 item，不复制到 execution。
- `DoclingChunkMapper` 使用既有 `IdGen` 为每个 Segment 生成普通 segmentId；`SegmentBulkWriter` 直接使用相同值作为 ES `_id` 写入，设置 `refresh=wait_for` 保证激活前新 generation 已可搜索，并拒绝空 ID、响应数量不一致和任一部分失败。
- INDEX finalizer 先校验当前 claim 并锁定 Asset，再清理同一未激活 target generation 的重试残留、bulk 覆盖写、CAS 激活 generation，最后在同一 MySQL 事务写旧 generation 清理 outbox 和 item COMPLETE。数据库提交失败时新 generation 留在 ES 但不满足 active gate；后续同 target 重试会先清掉残留。
- INDEX 终态失败也在失败 transition 的同一 MySQL 事务锁定当前 Asset：未激活 target 写入既有 `DELETE_ASSET_GENERATION`，随后由现有处理器统一删除该 generation 的 ES Segment，并按 item 请求快照删除内嵌图片目录。Finalizer 对 `target < active` 执行相同清理，对 `target == active` 明确禁删；非 INDEX 失败和已删除 Asset 不追加 generation 事件。Outbox 保存异常会回滚失败 transition。
- 搜索在 RRF 合并后、Rerank 前一次批量读取候选 Asset 的 active generation，按原顺序 fail-closed 过滤；全文读取在分页开始时固定同一个 active generation。generation 0 查询同时兼容显式 `0` 和旧文档缺字段。
- Segment Preview 与刷新入口也校验父 Asset 的 active generation；旧 generation、已删除 Asset 或不存在的 Segment 统一返回 `SEGMENT_NOT_FOUND`，不会通过旧 segmentId 绕过搜索可见性门禁。
- 普通删除与 overwrite 都在 Asset 行锁事务内 soft delete，并同时追加 `DELETE_ASSET` outbox；旧 generation 使用 `DELETE_ASSET_GENERATION` 复用现有 claim、lease、backoff 和失败重试，不再直接删 ES 或吞异常。
- JDK 21 全量回归共 541 项，0 failure、0 error、27 skipped；27 项均为当前机器无 Docker 而跳过的 Testcontainers 用例。107 新增回归覆盖 partial bulk 最终失败、未激活 target 清理、superseded target、active target 禁删、非 INDEX 失败、已删除 Asset 和 Outbox 回滚。`git diff --check`、全部 Mapper XML 校验、主代码编译和测试代码编译通过。
- 尚未执行业务库 V19、真实 Elasticsearch partial-bulk/DB-rollback/crash 故障演练或部署观察，因此当前状态不表示已迁移、发布或接管生产 INDEX 流量。`anchr-web`、`anchr-docling` 不在本卡实现边界内，没有生产代码改动。

---

## ANCHR-109：会话列表 keyset 分页与 Session 原子更新

**目标：** 完整加载超过 200 个会话，防止消息并发和重命名互相覆盖。

### 根因

会话列表使用 offset cursor，并查询 `offset + limit + 1`；Repository 又把查询总量封顶 200。前端已有自动和手动加载，因此问题在后端。

消息开始时加载整个 Session，模型完成后再全量 upsert。期间如果用户重命名或另一条消息完成，旧对象可能覆盖新 title，并把 updatedAt 写回较早值。

源码：

- [`ConversationServiceImpl.java`](../src/main/java/com/anchr/core/conversation/application/impl/ConversationServiceImpl.java)
- [`ConversationRepositoryImpl.java`](../src/main/java/com/anchr/core/conversation/infrastructure/persistence/ConversationRepositoryImpl.java)
- [`ConversationMapper.xml`](../src/main/resources/mapper/conversation/ConversationMapper.xml)
- [`anchr-web/src/features/ask/ask-premium-page.tsx`](../../anchr-web/src/features/ask/ask-premium-page.tsx)

### 修复方案

利用现有 `(user_id, deleted_at, updated_at, session_id)` 索引改为 keyset：

```sql
where user_id = ? and deleted_at is null
  and (updated_at < ? or (updated_at = ? and session_id < ?))
order by updated_at desc, session_id desc
limit pageSize + 1
```

cursor v1 包含 version、updatedAt、sessionId；前端继续视为 opaque string。后端严格拒绝旧 offset、未知版本和畸形 cursor，不能静默退回第一页；前端在追加请求收到 `INVALID_REQUEST` 时清空旧分页链并重拉第一页，以兼容 Preview 最多 6 小时的旧 cursor 缓存。发布顺序为前端先、后端后。

删除生产可调用的 Session 全量 upsert，首次创建改为纯 insert；后续拆为：

- `renameSession`
- `touchSessionIfNewer`
- `updateAutoTitleIfUnchanged`

自动标题使用 CAS：只有 title 仍等于请求开始时的旧值才更新。手动重命名后 CAS 自动失败。rename、touch 和成功的 title CAS 都使用 `greatest(timestampadd(microsecond, 1000, updated_at), candidateTime)`，让 `updatedAt` 在数据库行锁下严格递增；它既不因逆序完成而回退，也不会让同毫秒的两个元数据版本无法定序。Turn 保存、自动标题 CAS/touch 在同一事务完成；CAS 失败仍必须 touch。消息结束后重新读取数据库 Session，将当前 title 和 sessionUpdatedAt 返回给 REST/SSE，前端不得用较旧的 done 事件覆盖更新后的标题。

### 边界

本卡只修改 Session 列表游标和 Session 元数据的原子更新方法。消息历史 `beforeTurnId`、消息内容、Agent Activity 恢复、SSE/REST DTO 分层均不修改；仅在现有 done payload 中增加向后兼容的 `sessionUpdatedAt` 新鲜度字段。202 后续只能搬移 DTO 边界，204 只能把本卡已经验证的 CAS/单调规则收口为领域方法，不能改变结果。

keyset 不是严格快照：已经返回的记录因 updatedAt 单调不会向后重复，但尚未返回的 Session 如果在翻页期间更新并移动到 cursor 前，本轮仍可能遗漏。仅以首次查询时间增加 `updated_at < baseTime` 不能恢复行的历史位置，因此不冒充快照；若产品要求首次集合每条严格返回一次，应另立物化 ID 列表或历史版本任务。

### 保持不变与验收

消息历史 `beforeTurnId` 已正确实现 keyset 和前端 prepend，不纳入本任务；Agent Activity 恢复逻辑也不改。

- 静态数据集下 500 个会话可完整分页，无重复和 200 条截断。
- 同 updatedAt 的会话分页稳定。
- 生成期间手动重命名不被覆盖。
- 两条消息逆序完成时 updatedAt 不回退。
- 前端自动/手动加载和缓存恢复正常。

---

## ANCHR-110：文档内嵌图片制品化、独立 Segment 与跨模态检索

**状态：** 主体源码与本地回归已完成；“Docling 已上传但 item 尚未完成索引”的终态失败窗口通过 attempt 独占目录、item 请求快照和既有 Outbox 补偿；真实 OSS/Elasticsearch、存量 PDF/Markdown reparse、101C physical index 发布及部署验收待完成。功能开关继续保持默认关闭；本任务不新增业务表迁移。

**目标：** 将 PDF/Markdown 中的内嵌图片从“Docling 上传后无人消费的临时 URL”升级为父文档下可追踪、可清理、可重建、可检索的 `DOCUMENT_IMAGE` Segment。第一阶段必须支持“文本查询通过图片视觉语义命中父 PDF/MD”，继续使用唯一 `embedding` 字段，并保证点击结果仍打开父文档对应页而不是孤立 PNG。

### 当前流程与根因

当前 Docling 在 chunks 输出模式下上传图片，并向顶层 `images[]` 写入：

```text
url / pageNo / blockId / alt
```

但该结构尚不能作为图片制品和 Segment 的可靠输入：

1. `images[]` 没有 `bbox`、图片宽高、mimeType、内容 hash、上传状态或稳定 object key；Java `ParseResponse.Image` 同样只声明上述四个字段。
2. `chunks[].bboxes` 是整个 chunk 内多个 Docling item 的混合集合；一个 chunk 可以同时包含正文、表格和多张图片，不能按顺序或 pageNo 反推出某个 `images[].blockId` 的图片 bbox。
3. 图片 bbox 必须从对应 Picture Item 自身的 provenance 提取，并在 Docling 边界内通过 `blockId` 与图片一一绑定。PDF 等有页面坐标的输入必须返回准确 bbox；Markdown 外链图片通常没有页面坐标，`pageNo/bboxes` 允许为空，禁止伪造坐标。
4. Docling 返回的是普通 virtual-hosted URL；后端其他 OSS 读取使用临时签名 URL。私有 bucket 下，裸 URL 不能作为稳定 AI 输入、预览地址或持久化身份。
5. `DoclingChunkMapper` 只读取 `response.chunks()`，完全丢弃 `images[]/warnings[]`；同时 `textPlain` 会删除 Markdown 图片占位以及 alt，图片不会进入当前 BM25、embedding、citation 或 preview。
6. 当前图片对象位于 parse attempt 独占目录；重新解析会产生新目录，因此删除、overwrite 和 generation 退休必须使用 item 请求快照中的稳定目录，不能依赖 ES Segment 反查。
7. 当前 physical index rebuild 以 `assetType == IMAGE` 决定是否使用图片输入。文档内图片的 `assetType` 必须仍是 `PDF/MARKDOWN`，因此若只新增 Segment，重建会错误地按文本重算或因无文本失败。
8. 当前统一搜索对所有 Segment 使用一个全局 vector topK，文本 Segment 数量远多于图片；图片可能在召回窗口被文本挤出。Rerank 只读取文本字段，父资产聚合又发生在 Rerank 之后，大量同文档图片还会提前占满候选窗口。

源码：

- [`anchr-docling/src/anchr_docling/images.py`](../../anchr-docling/src/anchr_docling/images.py)
- [`anchr-docling/src/anchr_docling/docling_parser.py`](../../anchr-docling/src/anchr_docling/docling_parser.py)
- [`anchr-docling/src/anchr_docling/chunking.py`](../../anchr-docling/src/anchr_docling/chunking.py)
- [`ParseResponse.java`](../src/main/java/com/anchr/core/common/model/ParseResponse.java)
- [`DoclingChunkMapper.java`](../src/main/java/com/anchr/core/ingestion/infrastructure/parser/DoclingChunkMapper.java)
- [`IngestionTaskProcessorImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionTaskProcessorImpl.java)
- [`SegmentIndexManagerImpl.java`](../src/main/java/com/anchr/core/search/application/impl/SegmentIndexManagerImpl.java)
- [`RetrievalQueryServiceImpl.java`](../src/main/java/com/anchr/core/search/application/impl/RetrievalQueryServiceImpl.java)
- [`SegmentPreviewServiceImpl.java`](../src/main/java/com/anchr/core/search/application/impl/SegmentPreviewServiceImpl.java)
- [`anchr-web/src/features/search/search-premium-page.tsx`](../../anchr-web/src/features/search/search-premium-page.tsx)

### 一、升级 Docling 图片制品契约

禁止让 Docling transport DTO 直接泄漏为 Search Segment。Docling v3 输出版本化 `EmbeddedImageArtifact`，app 的 parser adapter 先映射为 Ingestion 域值对象，再由投影器生成 Segment：

```text
EmbeddedImageArtifact {
  blockId
  imageObjectKey
  uploadStatus = UPLOADED | SKIPPED | FAILED
  pageNo?                         // 无页面语义时为空
  bboxes?: [
    {
      pageNo,
      bbox: { l, t, r, b, coordOrigin }
    }
  ]
  imageWidth?
  imageHeight?
  mimeType
  contentHash
  alt?
  caption?
  contextText?
  ocrText?
}
```

契约要求：

1. `imageObjectKey` 是存储身份；响应可以提供仅供诊断的 URL，但 app 不持久化、不索引裸 URL。
2. `blockId` 在一次确定的 sourceRevision/parseAttempt 内唯一；相同业务重试返回同一 artifact identity 和 object key。
3. `bboxes` 只能来自该 Picture Item 自身的 provenance。不得复制整个 chunk 的 bboxes，不得使用“取第一个 bbox”或“同页即认为对应”的启发式逻辑。
4. bbox 坐标原点和单位必须显式返回并通过跨语言 fixture 固定；app 不在不知道页面尺寸/坐标系时自行翻转。
5. PDF 图片若 Docling 能提取图片但不能取得 provenance，仍可返回 artifact，但 `bboxes=null` 并产生结构化 warning；搜索可用，页面区域高亮降级。
6. Markdown 外链/相对路径/data URI 分别定义下载、allowlist、大小限制和失败语义。没有页面布局的 Markdown 图片允许 `pageNo/bboxes=null`，不能让缺失坐标导致整份文档解析失败。
7. AEAD 凭据协议消费 104 的安全要求：version/keyId/nonce/tag/expiration 和绑定 requestId、bucket、basePath、endpoint 的 AAD；Docling 只允许写入配置白名单中的 bucket/prefix。

`EmbeddedImageArtifact` 作为 Docling 成功 job 的 `ParseResponse.images[]` 返回并由 App 直接校验、映射；不持久化 Parse artifact 或第二份图片 manifest，也不增加图片专用业务表。

### 二、投影为父文档下的独立 Segment

每个成功图片制品生成一条独立记录：

```text
segmentType      = DOCUMENT_IMAGE
assetId          = 父 PDF/Markdown assetId
assetType        = 父资产类型 PDF/MARKDOWN
sourceRef        = artifact.imageObjectKey
pageNo/bbox      = artifact 的页面锚点
imageWidth/Height
contentText      = caption + alt + 章节标题 + 受限长度的相邻正文
ocrText          = 图片自身 OCR，可为空
embedding        = 101B Projection Policy 的输出
indexGeneration  = 107 当前目标 generation
```

`sourceRef` 统一表示支撑当前 Segment 的对象：普通文本 Segment 指向原文档，`DOCUMENT_IMAGE` 指向内嵌图片对象。父文档通过既有 `assetId -> Asset.objectKey/previewObjectKey` 定位，不在每条图片 Segment 中重复保存父对象 key，也不新增 `imageObjectKey`、`imageBlockId` 或 `parentSourceRef`。`blockId` 只在解析阶段校验和去重，不参与 Segment ID，也不进入 ES mapping；不得把签名 URL 拼进 `sourceRef` 或 `contentText`。

Segment ID 沿用普通 ID 契约：

```text
segmentId = IdGen.nextIdStr()
ES _id = segmentId
```

`blockId` 继续用于同一次 Parse 结果内的图片去重；去重后的图片按稳定列表顺序分配 `chunkOrder`。同一图片在多个 chunk 中被引用只能产生一个 Segment。需要匹配逻辑图片 Segment 时直接比较现有 `assetId + indexGeneration + segmentType + chunkOrder` 字段，不改变 ID。

图片对象目录由 `assetId + targetIndexGeneration` 确定为 `storagePrefix/ingestion/assets/{assetId}/generations/{generation}/images/`，无需在 ingestion item 保存请求快照。新 generation 激活时由已有 `DELETE_ASSET_GENERATION` 事件同时删除旧 generation 的图片目录和 Segment；Asset 删除复用已有 `DELETE_ASSET` 事件。清理失败沿用同一个 outbox 事件重试，不新增图片状态表或第二套 generation 生命周期，也不依赖 ES `_source` 作为对象删除清单。

### 三、单 embedding 的模型投影与切换

继续使用 ANCHR-101B 的唯一 `embedding` 字段和统一 `EmbeddingProjectionPolicy`：

| Serving/Target Profile | `DOCUMENT_IMAGE` 向量输入 | 文本字段用途 |
|---|---|---|
| 多模态 | `sourceRef` 中的图片对象 key 经 app 生成短期 AI 签名 URL，再以 `sourceType=IMAGE` 生成视觉向量 | `caption/alt/contextText/ocrText` 只参与 BM25、Rerank 和引用说明，不再写第二个 OCR dense vector |
| 纯文本 | 按固定顺序合并非空 `ocrText/caption/alt/contextText`，以 `sourceType=TEXT` 生成文本向量 | 同一文本同时用于 BM25/Rerank；全部为空时允许 Segment 无 dense vector |

Ingestion 和 rebuild 都必须按 `segmentType == DOCUMENT_IMAGE` 选择 `sourceRef` 指向的图片制品，而不是按父 `assetType`。多模态切到纯文本时重新生成文本向量，不复制旧视觉向量；切回多模态时从 `sourceRef` 重新签名并生成图片向量。图片对象在旧物理索引回滚观察期内不得提前删除。

仅执行 ES scroll/re-embedding 无法创造当前不存在的 `DOCUMENT_IMAGE` Segment。存量 PDF/Markdown 必须按 sourceRevision 重新 Parse，生成图片 artifact 和新的 Asset generation，再由 101C 的 physical index 部署能力完成 mapping 发布、影子校验和 alias 切换。

### 四、检索、Rerank 与父资产聚合

第一阶段查询仍是文本 query；多模态 serving profile 生成的文本 query vector 可以与同一空间内的图片向量比较。为避免图片被大量文本 Segment 挤出，同一个 `embedding` 字段建立按类型过滤的召回预算：

```text
BM25 route
  title/contentText/ocrText

TEXT vector route
  segmentType in (TEXT_CHUNK, IMAGE_OCR_BLOCK, ...)
  textTopK / textSimilarity

DOCUMENT_IMAGE vector route
  segmentType = DOCUMENT_IMAGE
  imageTopK / imageSimilarity

BM25 + 两个同字段 vector ranking
  → RRF
  → modality/asset diversification
  → Rerank
  → 父 asset 聚合
```

这里是同一 dense 字段的过滤查询，不是 `textEmbedding/imageEmbedding` 双字段。每个 route 的 topK、similarity 和最低覆盖率按 profile 独立配置并用离线 fixture 校准，不能直接假设现有全局 `0.75` 对文本-文本和文本-图片同样合适。

Rerank 规则：

1. 有 `caption/alt/contextText/ocrText` 的图片使用文本代理参与现有 Reranker。
2. 文本代理全空的纯视觉命中不得因空 Rerank document 被默认打成 0；保留规范化的 vector/RRF 分数，或进入明确的 modality-aware fusion 分支。
3. Rerank 前按 `assetId + segmentType` 做候选上限和多样化，避免一份多图 PDF 占满窗口；最终仍按父 `assetId` 聚合。
4. 聚合结果主记录保持父 PDF/MD 的 `assetType`，父文件名与 Preview 从 `assetId` 对应的 Asset 获取。图片命中作为 `topChunks` 中的 `DOCUMENT_IMAGE`，携带 segmentId、pageNo、bbox、图片 `sourceRef`、imagePreview 能力和 `hitSource=VECTOR|CAPTION|OCR`。
5. `resultType` 与 `segmentType` 分开建模；前端现有 `TEXT|IMAGE|MIXED` 结果类型不能接收后端直接返回的 `DOCUMENT_IMAGE` 枚举值。

纯文本 serving profile 下，`DOCUMENT_IMAGE` 只能通过 OCR/caption/alt/context 的 BM25/文本向量召回；UI 和能力接口不得宣称存在视觉语义检索。

### 五、Preview、Citation 与前端

点击 `DOCUMENT_IMAGE` 命中必须执行：

```text
assetId → Asset.previewObjectKey/objectKey → 父 PDF/MD previewUrl
                                      → 定位 pageNo
                                      → 有 bbox 时高亮图片区域
```

后端可以另返回由图片 Segment 的 `sourceRef` 临时签名的 `imagePreviewUrl/expiresAt`，供结果缩略图或侧栏查看，但它不能替代父文档 previewUrl。Preview URL cache key 必须包含对象身份，不能继续只按父 `assetId` 缓存后误把父文档 URL 与图片 URL 混用。

前端增加 `DOCUMENT_IMAGE` hit type、筛选标签、命中来源和缩略图展示；缺 bbox 时仍可打开父文档/Markdown，只是不做区域高亮。Conversation/Search Answer 当前只把文本 snippet 交给回答模型，因此第一阶段 citation 使用 caption/OCR/context 作为证据。让回答模型直接查看图片并解释图表属于独立的多模态 Answer 任务，不在本卡暗中开启。

### 查询模式边界

本卡第一阶段明确交付“文本查询 → 文档内图片向量 → 父 PDF/MD”。“用户上传一张查询图片 → 图片 query vector → 找到包含相似图片的 PDF/MD”需要新增 `queryType=IMAGE`、查询图片上传/权限/生命周期和 capability gate，不能伪装成现有必填字符串 `query`；若产品确认需要，应在本卡后续子卡或独立任务中交付，不作为第一阶段上线阻塞项。

### 发布与迁移

1. Docling 先支持旧/新图片契约双读写和 AEAD，但 app feature flag 仍关闭。
2. app 从 Docling 成功 job 直接消费 `EmbeddedImageArtifact`，新增 `DOCUMENT_IMAGE` Segment 类型并复用 `sourceRef` 和唯一 `embedding` 字段；搜索仍不暴露新 hit type。
3. 通过 101C 创建目标 physical index，并对存量 PDF/Markdown 执行可恢复 reparse/backfill；禁止只 scroll 旧 ES。
4. 影子验证图片数量、bbox 覆盖率、Docling 响应中的对象 key、向量维度、父资产聚合和相关性，再切 alias。
5. 后端开启图片召回 route，web 最后开放筛选、缩略图和 Preview；旧客户端忽略新增可选字段仍可使用。
6. 通过 107 已有 Asset/generation 可靠事件同步清理 Segment 和图片对象；保留既有 outbox 失败与重试统计。

### 边界

本卡唯一拥有 `ParseResponse.images[]` 的内嵌图片 schema、`DOCUMENT_IMAGE` Segment、既有 Asset/generation 清理事件中的图片对象删除、同一向量字段上的图片召回预算、Rerank 多模态公平性、父文档聚合和图片命中 Preview。它不新增图片专用生命周期表、状态、Outbox 类型或第二 dense 字段，不重定义 101B Projection Policy，不重建 101C profile 状态机，不复制 107 generation 语义，也不实现多模态回答生成或通用 DDD 搬包。失败 generation 的图片复用 `DELETE_ASSET_GENERATION` 清理。

### 验收

- PDF 中一个图片跨多个/混合 chunk 时只产生一个 `DOCUMENT_IMAGE`；bbox 精确来自该 Picture Item，不包含同 chunk 正文/其他图片 bbox。
- PDF 图片存在 provenance 时，Docling JSON → Java DTO → Ingestion 域对象 → ES Segment 的 pageNo、bbox、coordOrigin、宽高逐字段一致。
- Markdown 外链图片无 bbox 时解析、索引和检索成功；Preview 明确降级而不是伪造坐标或整份文档失败。
- 私有 bucket 不依赖裸 URL；embedding 和图片 preview 都由 app 根据图片 Segment 的 `sourceRef` 生成有期限、用途受限的签名输入。
- 同 parse attempt 重试、app 重启、部分 ES bulk 和 generation 重跑不产生重复 Segment/OSS 对象。
- reparse、overwrite、终态失败和 Asset 删除复用原有 generation/Asset 事件，并按 `assetId + generation` 清理独占图片目录；失败沿用同一 Outbox 重试器。
- 多模态 profile 下文本 query 能通过 `DOCUMENT_IMAGE` 命中父 PDF/MD；ES mapping 仍只有一个 `embedding` 字段。
- 纯文本 profile 下只使用 OCR/caption/alt/context 文本向量；全部为空时不写零向量、不复用旧视觉向量。
- 多模态 ↔ 纯文本切换后 Ingestion 与 rebuild 对同一 fixture 选择相同输入，父 `assetType=PDF/MARKDOWN` 不会导致图片按文本误投影。
- 一份多图文档不会独占召回/Rerank 窗口；聚合后结果数、顺序和 `topChunks` 可解释。
- 点击图片命中打开父文档对应页；有 bbox 时高亮图片区域，图片缩略图过期后可单独刷新，不改变父引用文件名。
- 既有 PDF/Markdown 文本 chunk、独立 IMAGE OCR/视觉检索、Search/Ask citation 和无图片文档行为不回退。

### 实施与验证记录

- `anchr-docling` 已提供 v3 `EmbeddedImageArtifact` Pydantic 契约，包含稳定 `imageObjectKey`、上传状态、Picture Item 全 provenance bbox、宽高、mime、内容 hash 与文本代理；Markdown 图片允许无 page/bbox。图片上传 key 继续绑定稳定 requestId，响应 URL 仅用于返回 Markdown，app 写制品前会剥离诊断 URL。
- App→Docling 临时凭据改为 AES-256-GCM envelope，包含 `version/keyId/nonce/ciphertext/tag/expiration`，AAD 固定绑定 `requestId/bucket/basePath/endpoint`；v2 fingerprint 继续兼容旧请求，启用内嵌图的新请求使用 contract v3。
- App 已消费 `images[]` 并去重生成 `DOCUMENT_IMAGE`，使用既有 `IdGen` 生成普通 ID；`sourceRef` 直接保存图片对象 key，`blockId` 只用于解析阶段去重且不持久化，父文档通过 `assetId -> Asset` 定位。TEXT profile 使用 `ocr + caption + alt + context`，MULTI profile 从 `sourceRef` 临时签名图片输入；rebuild 原样保留已有 `_id/segmentId/chunkOrder`。
- 图片列表由 App 在 Docling 成功 job 存续期间直接映射，不复制第二份图片清单。新请求使用 `assetId + targetGeneration` 独占图片目录和显式 key-layout 标记；PDF/Markdown 即使没有文本 chunk，只要存在有效 `DOCUMENT_IMAGE` 也可继续索引。
- `DELETE_ASSET_GENERATION` / `DELETE_ASSET` 按确定性的 `assetId + generation` 图片目录清理对象，再删除 ES Segment。终态失败复用 `DELETE_ASSET_GENERATION`；未新增图片表、图片状态或图片专用 Outbox 类型。
- 检索已拆为 BM25、普通同字段 vector route、`DOCUMENT_IMAGE` 同字段 vector route，分别配置 topK/similarity，RRF 后按 `assetId + segmentType` 限流再 Rerank、父资产聚合；`resultType` 保持 `TEXT/IMAGE/MIXED`，`topChunks` 保留 `DOCUMENT_IMAGE`、page/bbox、命中来源和短期图片缩略 URL。
- Preview 保持父 PDF/Markdown `previewUrl` 与 page/bbox 定位，另签发 `imagePreviewUrl/imagePreviewExpiresAt`；Preview cache key 已包含对象身份。Web 已增加文档图片筛选、标签、缩略图和预览侧栏，回答链路仍只消费文本代理，没有暗中开启多模态 Answer。
- 本地验证：`anchr-docling` 27 tests + 10 subtests 全通过，改动文件 Ruff 全通过；`anchr-app` 全量 534 tests 为 0 failure/0 error，27 个环境型 Testcontainers 用例按既有条件跳过；`anchr-web` 74 项测试全通过且生产构建通过。三仓 `git diff --check` 通过。
- 尚未执行真实私有 OSS 上传/签名/按前缀删除 smoke、真实 Elasticsearch mapping/KNN/相关性与多实例故障演练，也未批量 reparse 存量文档或执行 101C alias 发布。因此当前状态不表示已 stage、commit、开启 feature flag 或发布。

---

## DDD 与架构治理任务

以下任务以 `dev/clean-up@4dfa6b31` 的实际源码为准，不替代 ANCHR-101A–101C、102–107、109–110 的正确性责任。它们只把已经存在且已验证的规则收口到明确边界中。项目继续保持模块化单体，不拆微服务，也不对 Settings、Dashboard、Token 等简单 CRUD 强行套富领域模型。

## ANCHR-201：固化领域地图、状态所有权与交互决策

**目标：** 先把当前业务已经形成的一致性边界写清楚，作为后续拆分的共同语言；不引入 ArchUnit，不用目录规则代替业务判断，也不要求一次性重排现有 package。

**实施状态：已完成。** 决策基线见 [`docs/domain-boundaries-and-interactions.md`](../docs/domain-boundaries-and-interactions.md)。本卡只新增架构决策文档并更新任务状态，没有修改生产代码、Maven 依赖或 Spring wiring。

### 基于当前源码的领域地图

| 上下文 | 当前代码 | 拥有的状态/不变量 | 定位 |
|---|---|---|---|
| **Knowledge Content** | `kb` + `ingestion` | `knowledge_base`、`asset`、`ingestion_task`、`ingestion_task_item`；文档归属、去重、解析/索引执行、Asset active generation | 核心域；`kb` 与 `ingestion` 是同一上下文的 Catalog/Processing 子模块，不强拆 |
| **Retrieval** | `search` | ES Segment 投影、物理索引/alias、召回、generation 可见性过滤、RRF、rerank、结果聚合与 Preview | 核心域 |
| **Ask** | `conversation`（含 agent） | `conversation_session/turn`、`agent_task/run/step`；问答生命周期、证据消费、Agent 工具编排与两条流式协议的应用事件 | 核心域；Agent 保持内部子模块 |
| **Activity** | 当前散落在 `kb` 的 Activity/Recent | `activity_event` 与 Recent read model | 支撑域/读模型，不是 KB 聚合的一部分 |
| **Capability & Provider Configuration** | `settings` + `integration` 的配置、resolver、adapter | capability/storage 配置、serving provider 选择、外部模型/Docling/OSS client 适配 | 支撑域；保持事务脚本和 Adapter，不追求富领域模型 |
| **Auth / Technical Kernel** | `auth` + `common` 的稳定技术原语 | token、请求用户上下文、错误信封、ID/时间等 | 横切设施，不伪装成业务领域 |

这张图刻意不把现有每个顶层 package 都定义成 bounded context。源码中 Ingestion 创建、重试和 finalize 会在同一业务事务内锁定/更新 Asset 与 Item；`kb ↔ ingestion` 因此首先是包归属不清，不是两个领域之间缺少 Port。

### 聚合和一致性边界

- `KnowledgeBase` 独立存在，不加载全部 Asset；`Asset` 独立持有删除状态和 active generation。
- `IngestionTask` 是批次身份和创建幂等边界；计数/Items 列表是汇总查询。`IngestionTaskItem` 按 `itemId` 独立 claim、推进和重试，是实际执行一致性边界，不能塞回一个大 Task 聚合。
- generation 预留、Item fenced 状态、Asset 行锁、generation 激活和清理事件由 Knowledge Content 的 Application Coordinator 协调；数据库 CAS/锁仍是并发真相。
- `Segment` 是 Retrieval 的索引投影，不是 Asset 内部实体；`ConversationTurn` 是按 session 关联的追加记录；Agent Task/Run 各自按生命周期持久化，不并入 Session 大聚合。
- Settings、Activity、Token、Dashboard 等不需要为了“DDD 完整”补齐 Aggregate/Repository/Factory 套件。

### 跨领域交互模式

| 模式 | 何时使用 | 当前项目的目标用法 | 禁止替代成 |
|---|---|---|---|
| 同上下文直接协作 | 同一事务、同一不变量 | Catalog 与 Processing 共同更新 Asset/Item；允许内部 Repository/Coordinator 协作 | 为 `kb` 与 `ingestion` 每次调用都套防腐 Port |
| 同步 Query | 调用方立即需要只读事实 | Ask 查询 scope/文档引用；Retrieval 查询 active generation；Activity 查询 KB 名称 | 跨域 Repository、返回对方 Aggregate |
| 同步 Command | 调用方必须知道结果才能继续本地状态迁移 | Knowledge Content 请求 Retrieval 写入目标 generation；Retrieval alias 切换后激活 serving profile | 通用 command bus、无结果的“伪异步” |
| Application Process Coordinator | 一个流程跨 MySQL/ES/provider，且存在明确终态所有者 | Knowledge Content 拥有文档 generation 激活；Retrieval 拥有物理索引/profile 部署 | 宣称跨库 ACID、把编排塞入 Controller/定时器 |
| 可靠 Integration Event | 本地事务已完成，副作用可延迟且必须重试 | Asset 删除/旧 generation 退休后清理 Retrieval 投影与对象 | 所有内部方法调用都事件化 |
| after-commit best-effort 通知 | 丢失不影响业务正确性 | QUESTION/SEARCH/IMPORT/CITATION Activity | 为 Activity 建 Outbox 和全局事件平台 |
| outbound capability port | 外部技术能力可替换或失败 | generation/embedding/rerank/Docling/storage；按消费用例定义 | 一个覆盖所有模型/存储方法的万能 Port |

跨领域 Contract 只传不可变 ID、版本、generation、profile fingerprint 和 read snapshot，不传 Repository、MyBatis Record、REST DTO、Spring Web 类型或对方聚合。在 DDD 关系上，提供方的公开 Application API 是本模块化单体内的 Published Language；只有发生双向依赖或需要翻译外部协议时，消费方才增加防腐 Port，由组合层 Adapter 连接。业务上下文之间不建立 Shared Kernel。上述原则是设计评审清单，不做编译期强制规则。

### 上下文之间的明确交互

| 调用方向 | 模式 | 契约内容 | 结果/失败归属 |
|---|---|---|---|
| Knowledge Content 内 Catalog ↔ Processing | 同上下文直接协作 + 本地事务 | KB/Asset/Item、generation 预留与激活 | Knowledge Content；由 MySQL 锁/CAS 决定 |
| Knowledge Content → Retrieval | 同步 Command + 本地防腐 Adapter | `GenerationIndexer` 传目标 generation snapshot，返回 write receipt | Retrieval 保证幂等写；Knowledge Content 决定是否激活 |
| Retrieval → Knowledge Content | 同步 Query + 本地防腐 Adapter | `AssetGenerationLookup` 返回 scope、可见性和 active generation map | Knowledge Content 提供当前事实；Retrieval 据此过滤 |
| Knowledge Content → Retrieval/Storage | Outbox Integration Event | AssetDeleted、AssetGenerationRetired 的 ID/generation | Knowledge Content 保证投递；各 handler 幂等清理 |
| Ask → Knowledge Content | 同步 Query | 用户可见 KB、文档引用、active generation snapshot | Ask 处理 denied/not-found，不持有对方模型 |
| Ask → Retrieval | 同步 Query | search/read request，返回 evidence/content snapshot | Retrieval 负责召回与证据构造；Ask 负责回答 |
| Ask/Retrieval/Knowledge Content → Activity | after-commit best-effort | 已含 userId、resourceId 和展示 snapshot 的 Activity record | Activity 失败不回滚主流程 |
| 各核心上下文 → Capability Adapter | outbound port | generation/embedding/rerank/parse/storage 用例请求 | 调用上下文决定超时、降级或失败语义 |
| Capability → Retrieval → Capability | 同步部署流程 | 请求部署 desired profile；alias 成功后激活 serving profile | Retrieval 拥有物理索引终态，Capability 拥有 serving 配置 |

### 交付物与验收

- 在架构决策记录中保留上表、关键术语和每条现有跨域调用的目标模式；标明 `当前 → 目标 → 主责卡`。
- 为 `active generation`、`physical index version`、`embedding profile` 分别定义所有者，禁止继续混称“版本”。
- 对有争议的新调用先回答“状态归谁、是否需要立即结果、失败由谁恢复”，再决定 Query/Command/Event；不以 package 名判断。
- 本卡不改生产代码、不增加 Maven 依赖、不新增空壳 Port；评审通过即完成。

### 主要源码依据

- [`IngestionApplicationServiceImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionApplicationServiceImpl.java)：创建/重试会同时操作 KB、Asset、Item，证明它们共享业务一致性边界。
- [`IngestionIndexFinalizer.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionIndexFinalizer.java)：ES 写入、generation 激活、Item 完成与清理事件的现有编排。
- [`RetrievalQueryServiceImpl.java`](../src/main/java/com/anchr/core/search/application/impl/RetrievalQueryServiceImpl.java)：召回、active generation 过滤、融合、rerank 和聚合由 Retrieval 拥有。
- [`ConversationRetrievalAcl.java`](../src/main/java/com/anchr/core/conversation/application/acl/ConversationRetrievalAcl.java)：Ask 将自己的检索语义翻译为 Retrieval Application Query/Result。
- [`ActivityRecordServiceImpl.java`](../src/main/java/com/anchr/core/activity/application/impl/ActivityRecordServiceImpl.java) 与 [`OutboxEventProcessor.java`](../src/main/java/com/anchr/core/kb/application/impl/OutboxEventProcessor.java)：分别体现 best-effort Activity 与可靠清理事件的不同语义。
- [`CapabilityConfigServiceImpl.java`](../src/main/java/com/anchr/core/settings/application/impl/CapabilityConfigServiceImpl.java)：配置 CRUD、client 刷新与索引部署目前混在同一服务。

---

## ANCHR-202：能力提供方 API 与调用方 ACL

**目标：** 将本卡实际触及的跨领域直连收敛为“调用方 → 调用方 ACL → 提供方 Application API → 提供方实现”，只迁移 Ask/Retrieval/Knowledge Content 的查询链路，不提前实施 203–206。

### 实施范围

本卡只处理：

- Ask → Retrieval；
- Ask → Knowledge Content 的 KB scope；
- Retrieval → Knowledge Content 的 KB scope、active generation 和 Preview 所需 KB/Document 查询；
- 公共 Search 的 Retrieval API、Answer/Follow-up Application Result 和 REST DTO 适配；
- 将 `SEARCH_EXECUTED` 的触发从 Retrieval 实现移到公共 Search Controller，保证 Ask 内部检索不记录公共搜索行为。

明确不处理 Ingestion 索引写入、Agent Repository 直连、Activity API 重构、Capability、Outbox、SSE，也不修改数据库、Flyway、ES mapping、搜索参数、模型调用顺序或前端。

### 已落地的交互方式

```text
Conversation ─→ ConversationKnowledgeAcl ─→ KnowledgeContentQueryApi
Conversation ─→ ConversationRetrievalAcl ─→ RetrievalHitQueryApi

Search REST ─→ SearchRestAssembler ─→ RetrievalPageQueryApi
Retrieval ─→ SearchKnowledgeAcl ─→ KnowledgeContentQueryApi
Preview ─→ SearchKnowledgeAcl ─→ KnowledgeContentQueryApi
```

1. Knowledge Content 公开 `KnowledgeContentQueryApi`，返回 `KnowledgeBaseSummary`、`DocumentSummary` 和 active generation map；接口只给通用事实，不接受 requested scope，也不执行 Conversation/Search 的求交规则。
2. Conversation 与 Search 各自使用具体 ACL：`ConversationKnowledgeAcl`、`SearchKnowledgeAcl`。两者分别保持空 scope 返回全部 ACTIVE KB、指定 scope 求交、去空去重和请求顺序；不再共享 `KbScopeResolver`。
3. Retrieval 公开 `RetrievalHitQueryApi` 与 `RetrievalPageQueryApi`，请求和返回均为 immutable Application records。原 `UnifiedSearchService` 删除，`RetrievalQueryServiceImpl` 保持原 BM25/KNN/RRF/rerank/generation gate/聚合/指标行为。
4. `ConversationRetrievalAcl` 保留 `ConversationRetrievalOrchestrator` 入口，将 Ask 参数翻译为 `RetrievalHitQuery`，并将 hit/top chunk/anchor/explain 翻译回 Conversation candidate。Agent 仍通过该入口间接检索；其 Repository 直连留给 204。
5. `SearchRestAssembler` 负责现有 `SearchQueryDTO/SearchPageDTO/SearchResultDTO/RetrievalInsightDTO/SearchAnswerDTO` 与 Application records 的边界映射。`SearchAnswerService` 和 `SearchFollowUpService` 只消费已返回的 `RetrievalHit`，不再自行调用 Retrieval。
6. `SegmentPreviewServiceImpl` 继续拥有原错误转换、签名 URL、Activity 和 Preview DTO 构造，只通过 `SearchKnowledgeAcl` 查询 KB/Document 与 active generation。

### 兼容边界

- `POST /api/v1/search/kb` 的路径、method、认证、请求 JSON 和响应 JSON 保持不变；Preview、Conversation、Ingestion、Index、Activity 端点均未修改。
- 未修改 `anchr-web`，因为没有端点或 JSON 协议变化。
- 未增加 ArchUnit、通用 Bus、`RetrievalIndexApi` 或新的框架约束。
- Activity 仍使用现有 `ActivityEventService`；其独立边界留给 205。SSE 留给 206，Ingestion → Retrieval 留给 203。

### 实施与验证记录（2026-07-29）

- 已删除 `UnifiedSearchService`、`KbScopeResolver`，并迁移原检索测试到 Application Result。
- 202 目标测试通过：Knowledge Content API、两个 scope ACL、Conversation Retrieval ACL、generation gate、Preview、Answer、Follow-up、REST assembler、HTTP contract 和 Conversation 回归测试。
- HTTP golden contract 固定验证 `POST /api/v1/search/kb`，同时验证 Retrieval 收到 rewritten query、Activity 收到原 `SearchQueryDTO`，且响应仍包含原有 item、rewrite、intent、insight 和 suggestedQuestions 字段。
- `mvn -DskipTests compile` 与 `mvn -DskipTests test-compile` 通过。
- 沙箱内全量测试因本地 socket 权限出现 20 个环境错误；在允许本机临时端口后复跑全量 `mvn test`，共 495 个测试，0 failure、0 error、30 skipped。跳过项为当前机器无 Docker 时的 Testcontainers 既有测试。
- 本卡达到“目标测试、完整编译、HTTP golden contract 通过”的完成条件；真实 ES/数据库/对象存储部署验收不在本卡实施范围，不能由本地测试推断为生产验收。

---

## ANCHR-203：收口 Knowledge Content 与 Retrieval 的一致性边界

**目标：** 让 Knowledge Content 拥有 Asset/Item/generation 的业务终态，让 Retrieval 拥有 ES 投影与索引拓扑；用显式流程处理 MySQL 与 ES 不能共同回滚的事实。

### 当前事实与根因

`IngestionIndexFinalizer` 当前在一个 `@Transactional` 方法中执行：删除目标 generation → ES bulk write → CAS 激活 Asset generation → 记录旧 generation 清理事件 → 完成 Item。ES 成功后即使 MySQL 回滚，ES 写入也不会回滚；因此这不是跨库事务，只是把远程副作用包在了数据库事务外观里。

同时，Ingestion 直接使用 Search `Segment`、`SegmentRepository`、`SegmentBulkWriter` 和 REST `SegmentIndexStatusDTO`；Search 又通过 `AssetRepository/KnowledgeBaseService` 读取 active generation 与 scope。双方确有业务协作，但不应共享持久化模型。

### 实施后的流程

```text
Knowledge Content                              Retrieval
1. 既有短事务预留 target generation
2. IngestionRetrievalAcl ───────────────────→ RetrievalGenerationIndexApi
                                      replace 同一 target generation
                                   ←────────── RetrievalGenerationWriteReceipt
3. IngestionIndexFinalizer 短事务：重新锁定 Item/Asset
   CAS 激活 target generation
   完成 Item + 写入 generation-retired Outbox
4. Outbox → KnowledgeRetrievalCleanupAcl ──→ RetrievalCleanupApi
                                      幂等删除旧/失败 generation
```

1. 沿用 202 的轻量模式：提供方只暴露 Application API，调用方只增加一个具体 ACL；不再增加 `GenerationIndexer` 接口和本地 Adapter 空转层。
2. Ingestion 使用自己的 immutable generation snapshot，经 `IngestionRetrievalAcl` 转成 Retrieval Published Language；不再构造 Search `Segment`，也不再调用 Search Repository、ES document、bulk writer 或 REST DTO。
3. Retrieval 自己执行“删除同一 target generation 残留 → bulk 重写”，并返回 written count、index name 和 profile fingerprint。相同 generation 重放仍是覆盖，不是追加。
4. `IngestionIndexFinalizer` 只执行短 MySQL 事务：复核 write receipt、锁 Item/Asset、校验 previous generation、CAS 激活、完成 Item，并写旧 generation 清理 Outbox；事务和行锁不再覆盖 ES 调用时间。
5. ES 写入失败、部分成功、成功后 Item 状态变化或 Asset 删除时，未激活 generation 保持不可见，并由现有 `DELETE_ASSET_GENERATION` Outbox 可靠清理。只有数据库中的 active generation 与目标相等时才禁止清理，避免误删当前可见数据。
6. Outbox 保留原事件、payload、对象图片清理、重试与保留策略，只把 ES 删除从直接调用 Search Repository 改为 `KnowledgeRetrievalCleanupAcl → RetrievalCleanupApi`。
7. 202 已完成的 `SearchKnowledgeAcl → KnowledgeContentQueryApi` 继续承担 scope、active generation 和 Preview 查询；203 不重复改造查询链路。

### 同上下文内部边界

- 保留 `kb`/`ingestion` 现有 package 作为渐进子模块，不先做大规模搬包；允许一个 Knowledge Content transaction coordinator 同时使用 Asset 与 IngestionItem Repository。
- 选择性把已经稳定的 `claim/advance/complete/fail/assign generation` 和 Asset 激活前置条件提炼为 Policy；数据库 CAS、行锁和受影响行数继续是最终并发门禁。
- Task 汇总继续由 item 状态派生；worker 处理单个 Item 时不加载整个批次。

### 验收

- ES 远程调用不再发生在持有 Item/Asset MySQL 事务期间；锁时长不包含 bulk write。
- 故障测试覆盖 ES 全失败/部分成功、ES 成功后激活 CAS 失败、Asset 并发删除、worker 重放与旧 generation 清理；任何失败 generation 都不会变成 active。
- Knowledge Content 不再调用 Search Repository/ES document；Retrieval 不再调用 Asset/KB Repository。
- 文档成功、失败、重试、覆盖导入、active generation 可见性和 101B/101C/107/110 的既有行为不变。

### 范围边界

- 本卡只处理 generation 写入、短事务激活和 Retrieval 清理。
- 不处理 Capability/profile 部署、Storage/StorageConfig、Activity、Agent、SSE 或 Application Service 拆分；分别保留给 204–206。
- 不修改数据库、Flyway、ES mapping、索引参数、端点路径、HTTP method、认证、请求/响应 JSON 或前端。

### 实施与验证记录（2026-07-29）

- 新增 `RetrievalGenerationIndexApi`、`RetrievalCleanupApi` 及 immutable request/receipt/command records；调用方新增具体类 `IngestionRetrievalAcl` 和 `KnowledgeRetrievalCleanupAcl`，没有增加同义 ACL 接口。
- 原 Ingestion `SegmentBulkWriter` 已归到 Retrieval 的 ES infrastructure；Ingestion generation 链路不再依赖 Search Repository、ES document 或 REST DTO。
- 单元测试覆盖 generation snapshot 映射、边界校验、delete-before-write、同 generation 重放、bulk 部分失败、写入后激活顺序、receipt 校验、Item 状态变化、Asset 删除、active generation 防误删，以及 Outbox cleanup 重试。
- 未修改任何 Controller、端点或 JSON，因此 `anchr-web` 无需改动。
- `mvn -DskipTests compile` 通过；沙箱内全量测试仅因 20 个本机临时端口权限错误失败，在允许本机端口后复跑全量 514 项，0 failure、0 error、30 skipped。30 项仍是当前机器无 Docker 而跳过的 Testcontainers 测试，真实 MySQL/ES 故障演练与部署验收不能由本地结果替代。

---

## ANCHR-204：收口 Ask 剩余的 Knowledge/Retrieval 同步读取边界

**目标：** 202 已完成普通 Ask 的 KB scope 和检索 API；204 只收口剩余 Agent/异步文档总结直连，让 Ask 只看到自己的文档引用和证据模型，不接触 KB/Asset/Segment Repository、对方聚合或 Search REST DTO。

### 实施前源码事实

202 已稳定以下链路，本卡不重做：

```text
ConversationService → ConversationKnowledgeAcl → KnowledgeContentQueryApi
普通 Ask / search_knowledge → ConversationRetrievalOrchestrator
                           → ConversationRetrievalAcl
                           → RetrievalHitQueryApi
```

当前剩余直连不只在两个 Tool：

1. `AgentRequestContextResolver` 直接使用 `KnowledgeBaseRepository + AssetRepository` 生成模型可见的 selected KB/Asset context。
2. `AgentScopeGuard` 直接使用 `AssetRepository`，实现 assetId、完整文件名/标题解析以及 `PERMISSION_DENIED / AMBIGUOUS_DOCUMENT / DOCUMENT_NOT_FOUND` 转换。
3. `FindDocumentsTool` 直接查询 Asset 元数据，再与现有 Retrieval hit 合并排序。
4. `ReadDocumentTool` 直接按 active generation 分页读取 `SegmentRepository`。
5. `AgentTaskProcessor` 的异步多文档总结也直接查询 Asset active generation 和 Segment page；原卡遗漏了这条链路。
6. `ConversationMessagePipeline` 仍直接调用 Search 的 `CitationReasonGenerationService`；Pipeline、Agent state 和两个 Tool 仍引用 Search `SegmentType`。
7. `ConversationResultCardMapper/ResultHitDTO` 仍复用 Search REST `PreviewAnchorDTO`，导致两个接口层共享 DTO 所有权。

Agent Task/Run/Step 与 Conversation Session/Turn 仍属于同一个 Ask 上下文。本卡不拆 Agent，不改变它们的生命周期。

### 204A：Knowledge Content 文档引用能力

沿用 202 的提供方 API + 调用方具体 ACL，不增加 `AgentKnowledgePort → Adapter → Provider API` 空转层：

```text
AgentRequestContextResolver / AgentScopeGuard / FindDocumentsTool
  → ConversationKnowledgeAcl
  → KnowledgeContentQueryApi
  → Knowledge Content Repository 实现
```

1. 复用现有 `listActiveKnowledgeBases()`、`findActiveDocument(kbId, assetId)`；只补真实缺口 `searchActiveDocuments(kbId, keyword, limit)`，返回 `DocumentSummary`，不接受 Agent 的 requested scope，也不抛 Agent 错误。
2. 扩展现有具体类 `ConversationKnowledgeAcl`，不新增 `AgentKnowledgeAcl`。ACL 把 provider summary 转成 Ask 自己的 immutable `ConversationKnowledgeBaseReference / ConversationDocumentReference`；文档引用至少包含 id、kbId、fileName、title、fileType/mimeType 和 active generation。
3. requested KB 求交、输入顺序、去空去重、显式 asset scope、完整文件名/标题精确匹配和错误语义仍由 Ask 保持；Knowledge Content 只回答“当前 ACTIVE 的事实”。
4. `AgentRequestContextResolver` 保持 KB 50、Asset 20 的截断、名称清洗、selectionMode、count/truncated 字段和请求顺序。
5. `AgentScopeGuard` 改为返回 Ask 文档引用，不再把 KB `Asset` 聚合交给 Tool。原有五类结果保持：空引用、唯一 ID、唯一名称、同名歧义、超出显式 scope/不存在。
6. `FindDocumentsTool` 继续执行“元数据候选 + 现有 Retrieval hit 合并”，保持 limit、显式 asset 过滤、排序、snippet、matchedSegmentId 和 evidence 数量；不把文档发现改造成新的搜索算法。

### 204B：Retrieval 有序文档内容能力

增加一个真实缺口，不复制 202 的 Hit API：

```text
ReadDocumentTool / AgentTaskProcessor
  → ConversationRetrievalAcl
  → RetrievalDocumentContentQueryApi
  → Retrieval Segment Repository 实现
```

1. 新增 `RetrievalDocumentContentQueryApi.query(RetrievalDocumentContentQuery)`；请求只包含 kbId、assetId、明确的 active generation、afterChunkOrder、afterSegmentId 和 limit。
2. 返回 immutable `RetrievalDocumentChunk` 列表，字段只覆盖 Ask 阅读和 citation 所需的 segmentId、kbId、assetId、generation、assetType、segmentType、title、content、pageNo、chunkOrder、bbox、sourceRef；不暴露 `Segment`、embedding、ES document 或 Repository cursor object。
3. 扩展现有具体类 `ConversationRetrievalAcl` 映射为 Ask 自己的 document chunk/evidence；不新增 `AgentRetrievalAcl`，也不让 Tool 直接使用 Retrieval Application records。
4. `ReadDocumentTool` 仍先通过 `ConversationKnowledgeAcl`/`AgentScopeGuard` 获取当前 active generation，再读取 Retrieval。现有 Base64 cursor `chunkOrder:segmentId`、limit+1、最小 page size 10、最大输入 20、20,000 字符上限、nextCursor/hasMore JSON 和 filename `sourceRef` 保持不变。
5. `AgentTaskProcessor` 在异步任务真正执行时重新验证每个文档仍 ACTIVE，并使用当时的 active generation；继续每页 20 条、续租、总 segment/字符限制和 `DOCUMENT_NOT_FOUND / DOCUMENT_TOO_LARGE / NO_DOCUMENT_CONTENT` 结果。不得信任任务 request JSON 中的 generation，也不得改变 map/reduce/finalize prompt、模型调用次数、citation 选择或完成事务。
6. `search_knowledge` 和 `find_documents` 的内容召回继续走 202 的 `ConversationRetrievalOrchestrator`；不再新建同义 `KnowledgeSearchQuery`。

### 204C：剩余 Published Language 与 DTO 所有权

1. 将 `CitationReasonGenerationService` 收口为 Retrieval `application.api` 的窄 `RetrievalCitationReasonApi` 和 immutable records；Search Answer 直接消费 provider API，Ask 经 `ConversationRetrievalAcl` 调用。提示词、批量顺序、降级和 reason 回填行为不变。
2. `IMAGE_VISUAL` 不能作为回答证据的规则保留，但由 Ask 自己的 evidence model/helper 判断；`ConversationMessagePipeline`、`AgentRunState`、`SearchKnowledgeTool`、`FindDocumentsTool` 不再 import Search Domain `SegmentType`。ACL 内只依赖 Retrieval `application.api` Published Language。
3. `ResultHitDTO.anchor` 改为 Conversation 自有的同形 Anchor DTO，删除对 Search `PreviewAnchorDTO` 的复用；字段名、nullable、bbox 和最终 JSON 完全不变。

### 硬边界与明确不做

- 不修改任何端点路径、HTTP method、认证、请求 JSON、响应 JSON、SSE event 名称或字段；因此不修改 `anchr-web`。实施中若发现必须改变协议，立即停止并先确认。
- 不重做 202 的普通 Ask scope、rewrite、Hit Query、结果卡排序和公共 Search；不修改 BM25、KNN、RRF、rerank、generation gate 或 Preview。
- 不修改 Agent Tool 名称/描述/参数、工具选择与调用顺序、系统提示、预算、READ_LIMIT_REACHED、citation marker 或最终回答落库顺序。
- 不修改 Agent Task claim/lease/retry/cancel、Run/Step trace、两条 SSE、断线恢复或 Session rename/touch/auto-title CAS；这些不是跨域读取问题。
- 不处理 Activity、Capability/provider 配置、Storage、Outbox、数据库、Flyway、ES mapping 或索引写入；分别保留给 205/既有卡。
- 不拆 `AgentWorkflowImpl`、`AgentTaskProcessor`、`ConversationServiceImpl` 大类；机械拆分类只归 206。
- 不新增 Agent bounded context、微服务、Command/Event Bus、跨域缓存或最终一致 scope 投影。

### 验收与测试

- `conversation` 除 `application.acl → kb/search application.api` 外，不再 import KB/Asset/Segment Repository、KB/Search 聚合、Search Domain model 或 Search REST DTO。
- Knowledge provider 测试覆盖 ACTIVE KB/document、metadata keyword/limit、missing；Conversation ACL 覆盖请求顺序、去重、显式 scope、ID/名称唯一匹配、歧义和错误映射。
- Retrieval content API 测试覆盖 generation 条件、稳定 `(chunkOrder, segmentId)` 顺序、首/中/末页、空页、字段映射和重复请求。
- `ReadDocumentTool` golden test 固定 cursor、limit、20,000 字符截断、nextCursor、sourceRef 和 evidence；`AgentTaskProcessor` 固定执行时二次 active 校验、分页/续租、上限错误和 citation 字段链。
- `AgentRequestContextResolver`、`AgentScopeGuard`、`FindDocumentsTool`、`SearchKnowledgeTool`、普通 Ask pipeline、citation reason、result card/anchor 和异步总结 characterization tests 全量迁移，行为与模型输入不变。
- 固定验证现有 Conversation/Agent HTTP 与两条 SSE golden contract；完整 `mvn compile/test` 通过，并单独报告无 Docker 的 Testcontainers 跳过项。

### 实施与验证记录（2026-07-29）

- 204A 已完成：`KnowledgeContentQueryApi` 补充通用的 ACTIVE 文档元数据查询；`ConversationKnowledgeAcl` 统一把提供方 summary 转成 Ask 自有文档引用。`AgentRequestContextResolver`、`AgentScopeGuard` 和 `FindDocumentsTool` 不再直接依赖 Knowledge Repository 或 `Asset` 聚合，原 scope、精确名称匹配、歧义和错误语义保持不变。
- 204B 已完成：新增 `RetrievalDocumentContentQueryApi` 与 immutable query/chunk records；`ReadDocumentTool`、`AgentTaskProcessor` 经 `ConversationRetrievalAcl` 读取指定 active generation 的有序内容，不再直接依赖 `SegmentRepository` 或 `Segment`。cursor、limit、字符/segment 上限、执行时二次 ACTIVE 校验、续租、prompt 和 citation 流程未改。
- 204C 已完成：citation reason 收口为 Retrieval Application API，Ask 经自身 ACL 调用；Ask 的 `IMAGE_VISUAL` evidence 判断不再引用 Search Domain `SegmentType`；结果卡 anchor 改为 Conversation 自有同形 DTO。Conversation 主源码中已无 KB/Search Domain model、Repository、Search REST DTO 或 Search Application impl 依赖。
- 未修改任何 Controller、端点路径、HTTP method、认证、请求/响应 JSON 或 SSE 事件，也没有修改 `anchr-web`。Activity 直连按硬边界保留给 ANCHR-205。
- 定向测试覆盖 provider/ACL 映射、文档 metadata、generation 有序内容、Agent scope、Read Document、异步总结相关路径、citation reason、visual evidence 和 result card；`mvn -DskipTests compile` 与 `test-compile` 通过。
- 沙箱内全量测试仅因 20 个本机临时端口权限错误失败；允许本机端口后复跑全量 519 项，0 failure、0 error、30 skipped。30 项仍是当前机器无 Docker 而跳过的 Testcontainers 测试；本地结果不代表真实 MySQL/Elasticsearch/对象存储或部署验收。

**实施状态：已完成。** 本卡的同步读取边界、目标回归、完整编译和现有协议回归均已通过；未提交、未合并、未发布。

---

## ANCHR-205：收口 Activity、Provider/Storage 与专用 Outbox 支撑边界

**目标：** 只处理 201–204 明确保留下来的支撑边界直连。Activity 拥有 `activity_event` 与 Recent 读模型；Capability 拥有模型/存储配置和 provider adapter；Knowledge Content 继续拥有 Asset/generation 清理 Outbox。三者都保持轻量事务脚本或 Adapter，不建设新的平台、富聚合或总线。

本卡按真实修改面拆为 205A–205D。每个子卡必须能独立编译、测试和回滚；不能把四段合成一次大搬包。205 总体工作量按当前源码应为 XL，不再低估为一张 L 卡。

### 当前源码事实

1. Activity 代码物理位于 `kb`，但 `activity_event`、Recent 去重/cursor 和 citation snapshot 不属于 KnowledgeBase 聚合。现有 `ActivityEventService` 还直接接收 Search REST DTO，`ActivityQueryServiceImpl` 直接读取 `KnowledgeBaseRepository`，`SegmentPreviewServiceImpl` 又直接消费 Activity 的 KB REST DTO。
2. Conversation 的 QUESTION 记录已经发生在 Turn 事务提交后；公共 Search 和 Preview 没有主业务写事务。Ingestion 的 DOCUMENT_IMPORTED 记录仍位于创建事务内，虽然 recorder 捕获异常，数据库异常仍可能污染同一事务。现有 append 记录没有显式携带 userId，而是由 Activity 在执行时读取 `UserContextHolder`。
3. Session 删除会同步删除对应 Activity；Asset 删除会同步删除该 Asset 的 citation Activity。这两条是用户可见清理，不等同于可丢失的 Recent append，不能统一改成 best-effort。
4. `settings + integration` 已共同构成 Capability 上下文。`CapabilityResolver`、client factory/cache 和 `ConfigDriven*Adapter` 在该上下文内部读取配置不是跨领域错误；这些 Adapter 已经是 Conversation/Retrieval/Ingestion outbound Port 的防腐实现，不再额外套一层空接口。
5. 205B 已收口 embedding 双向部署；205C 已收口 `AuthController/Ingestion/Outbox → StorageConfigRepository` 和 `IngestionTaskProcessorImpl → DoclingClient/StorageTokenIssuer`。Capability 的 Storage 配置只经 `StorageRuntimeApi` 暴露 location snapshot 与临时凭据。
6. 205C 已让 `AssetPreviewServiceImpl` 和 Outbox 改用 Knowledge Content 自有 `KnowledgeObjectStoragePort`，并删除没有生产调用的 `SearchObjectStoragePort.uploadFile(MultipartFile)`。同一个 ConfigDriven Adapter 继续实现各调用方窄 Port。
7. Outbox 已在 203 通过 `KnowledgeRetrievalCleanupAcl → RetrievalCleanupApi` 清理 ES。剩余的 `IngestionTaskRepository`、图片路径和 Asset/generation payload 都属于同一个 Knowledge Content，不需要再建 Ingestion provider API。

### 205A：Activity Published Language、调用方 ACL 与物理归属（已完成，2026-07-29）

调用链固定为：

```text
ConversationActivityAcl ─┐
SearchActivityAcl ───────┼→ ActivityRecordApi → Activity 实现 → activity_event
IngestionActivityAcl ────┤
KnowledgeActivityAcl ────┘

ActivityQueryApi → ActivityKnowledgeAcl → KnowledgeContentQueryApi
ActivityController → ActivityQueryApi → 现有 Recent JSON
```

1. 在 Activity `application.api` 暴露 `ActivityRecordApi`、`ActivityQueryApi` 和 immutable records。记录模型按 QUESTION、SEARCH、IMPORT、CITATION 分开，字段覆盖现有 payload；不接收 `SearchQueryDTO`、Preview DTO、KB DTO、Repository model 或任意 `Map<String,Object>` 业务输入。
2. `ConversationActivityAcl`、`SearchActivityAcl`、`IngestionActivityAcl`、`KnowledgeActivityAcl` 都是调用方 `application.acl` 的具体类，不再为 ACL 增加同义接口。它们负责把各自 DTO/model 转成 Activity record，并保持各调用方现有触发条件。
3. append record 必须在主业务事务提交后或无主事务时 best-effort 执行。命令在调用方提前捕获 userId、resourceId、展示字段和时间快照；Activity 不再依赖调用线程里的 `UserContextHolder`。记录失败只丢 Recent，不回滚 Turn、Search、Preview 或 Ingestion Task。
4. `deleteBySessionId` 与 `deleteCitationOpenedByAssetId` 作为 `ActivityRecordApi` 的同步维护命令保留在调用方现有 MySQL 事务中；失败继续阻止主删除提交，避免 Session/Asset 已删但其 Recent/citation 仍可见。不能把这两条误改成 best-effort。
5. Activity 查询当前 KB 名称时，经具体类 `ActivityKnowledgeAcl` 调用 `KnowledgeContentQueryApi`。只在确有批量需要时补通用 `findActiveKnowledgeBases(ids)`；provider 只返回 ACTIVE 事实，不处理 Activity cursor/去重。
6. Activity service/model/repository/mapper/REST DTO 从 `kb` 迁到顶层 `activity` package，ActivityController 仍保持原路径。只搬归属，不改 `activity_event` 表、Mapper SQL、payload key 或 Recent 算法。
7. `SearchActivityAcl` 同时适配 Preview 的 citation record/fetch；Retrieval 不再依赖 Activity 的 REST DTO。ActivityController 负责把 Application Result 映射回现有 Recent DTO。

**实施结果：**

- 已增加 `ActivityRecordApi`、`ActivityQueryApi` 和 Activity 自有 immutable record；Provider Application API 不再接收 Search/Preview/KB REST DTO 或调用方 Domain model。
- 已增加四个调用方具体 ACL。Ingestion 的 DOCUMENT_IMPORTED 在事务提交后追加，调用方提前捕获 userId/时间快照；append 全链路保持 best-effort。Session/Asset Activity 清理仍同步执行且异常不吞。
- Activity service/domain/repository/MyBatis/REST DTO/Controller 已从 `kb` 迁到顶层 `activity`；表、SQL、payload key、Recent cursor/去重和 `/api/v1/activity/**` 协议未改。
- Activity 查询 KB 名称已改为 `ActivityKnowledgeAcl → KnowledgeContentQueryApi.findActiveKnowledgeBases`；Search Preview 的 citation record/fetch 已改走 `SearchActivityAcl`。
- 目标回归测试通过；显式加载 Mockito agent 后执行干净的完整 `mvn clean test`：456 tests、0 failures、0 errors、16 个无 Docker 的 MySQL/Testcontainers 用例跳过。
- 205B、205C、205D 当时未执行；当前 205A–205D 均已完成，ANCHR-205 源码与本地回归已收口。

### 205B：Embedding 配置部署的双向 API（已完成，2026-07-29）

只收口 Capability 与 Retrieval 的双向部署，不重写所有 AI Adapter：

```text
CapabilityConfigServiceImpl
  → CapabilityRetrievalAcl
  → RetrievalEmbeddingDeploymentApi
  → Retrieval 物理索引重建与 alias 切换

SegmentIndexManagerImpl
  → RetrievalCapabilityAcl
  → CapabilityServingConfigApi
  → 激活 serving config + 刷新原 client cache
```

1. Retrieval 暴露 `RetrievalEmbeddingDeploymentApi` 和 immutable deployment request；Capability 暴露 `CapabilityServingConfigApi` 和 immutable activation command。双方都不传 `CapabilityConfig`、`EmbeddingProfile` Domain model、Repository 或 provider client。
2. `CapabilityRetrievalAcl` 将 desired config 转成 Retrieval request；`RetrievalCapabilityAcl` 将 alias 成功结果转成 serving activation。ACL 使用具体类，不增加同义 Port/Adapter 接口。
3. 保持当前选择语义：GENERATION/RERANK 立即选择；embedding profile 未变化时立即选择；profile 变化时只创建/保留 disabled desired config 并请求 Retrieval 部署；只有 alias 成功后才能激活新 serving config。任一步失败继续使用旧 serving config 和旧 alias。
4. `CapabilityConfigServiceImpl` 不再注入 `SegmentIndexManager`；`SegmentIndexManagerImpl` 不再注入 integration 的 `ServingEmbeddingConfigActivator`。Index Controller 和 Retrieval 内部仍可继续使用现有 `SegmentIndexManager`，本卡不重写索引管理内部接口。
5. Capability 内部用自己的 immutable profile snapshot/factory 计算 capability、model、dimension 和 fingerprint；`CapabilityConfigServiceImpl` 不再 import Retrieval `EmbeddingProfile`。现有 `CapabilityEmbeddingProfileProvider`、`CapabilityIndexDimensionProvider` 和 embedding Adapter 作为 Retrieval Port 的实现负责最后一跳映射，不把 Capability snapshot 暴露给 Retrieval 用例。
6. `CapabilityResolver`、`ClientCacheManager`、`CapabilityClientFactory`、connection test 和 `ConfigDrivenGeneration/Embedding/Rerank/SpringAiAgentModelAdapter` 保持 Capability 内部实现。Adapter 可以继续在语义相同时实现多个调用方 Port；不为了“每域一个 Adapter”复制模型调用代码。
7. 保持 optional wiring 和当前缺失能力/降级行为；不得把 optional embedding deployment 或 rerank/generation 能力改成启动期强依赖。

**实施结果：**

- Retrieval 已暴露 `RetrievalEmbeddingDeploymentApi`，Capability 已暴露 `CapabilityServingConfigApi`；两侧只交换 immutable Application record，不传 `CapabilityConfig`、`EmbeddingProfile`、Repository 或 provider client。
- 已增加具体类 `CapabilityRetrievalAcl` 与 `RetrievalCapabilityAcl`。`CapabilityConfigServiceImpl` 不再依赖 `SegmentIndexManager` 或 Retrieval `EmbeddingProfile`；`SegmentIndexManagerImpl` 不再依赖 integration 的 `ServingEmbeddingConfigActivator`。
- Capability 自有 `CapabilityEmbeddingProfileSnapshot/Factory` 保留原 fingerprint、dimension 和草稿判断算法；原 `CapabilityEmbeddingProfileProvider` 改为复用该工厂并只在 Adapter 边界映射为 Retrieval profile。
- GENERATION/RERANK、相同 embedding profile 和 optional deployment 缺失时仍立即选择；不同 profile 只请求 Retrieval 创建待重建任务，不提前启用目标配置。
- Retrieval 仍先切 alias，再经 `RetrievalCapabilityAcl` 激活 serving config 并刷新 embedding client cache；激活失败仍将 alias 切回旧索引，部署请求失败仍保留旧 serving 配置。
- 未修改端点、HTTP/JSON/SSE、前端、数据库、ES mapping、物理索引命名、重建算法或 provider 调用参数；205C、205D 当前也已完成。
- 目标测试通过；`mvn compile`、`test-compile` 通过；显式加载 Mockito agent 后完整 `mvn clean test` 通过：467 tests、0 failures、0 errors、16 个无 Docker 的 Testcontainers 用例跳过。

### 205C：Storage 与 Docling 调用边界（已完成，2026-07-29）

1. Capability 暴露窄 `StorageRuntimeApi`：查询当前 location snapshot，以及签发临时 STS credential。records 只包含 endpoint、bucket、region、prefix、临时 AK/SK/token/expiration 等当前调用确实需要的字段；不暴露 `StorageConfig`、Repository、加密密文或 `AesUtil`。
2. 新增具体类 `AuthStorageAcl`、`IngestionStorageAcl`、`KnowledgeStorageAcl`。Auth `/sts`、Ingestion embedded-image target/credential 和 Outbox 图片前缀查询都经各自 ACL；调用方不直接读取 Settings Repository。
3. Ingestion 拥有自己的 `IngestionStorageTarget`。它仍在单次处理开始时捕获 endpoint/bucket/basePath，在每次重提 Docling job 前重新签发临时凭据并校验 target 未变化；现有 requestId/sourceRevision、AAD、AES-GCM envelope、keyId、expiration 和失败语义完全不变。
4. `IngestionDoclingAcl` 作为具体防腐层包装现有 `DoclingClient`，把 job/status/error/retry-after 映射为 Ingestion 自有 records/exception；不新建万能 Provider API，也不改变 submit/get/ack、恢复次数、轮询、超时或模型调用顺序。
5. Knowledge Content 新增自己的 `KnowledgeObjectStoragePort`，只包含 Preview 签名和按前缀删除等真实用例；`AssetPreviewServiceImpl` 不再依赖 Search Port，Outbox 不再依赖 Ingestion Port。同一个 `ConfigDrivenStorageAdapter` 可以实现 Search/Ingestion/Knowledge 三个窄 Port，因为底层 OSS 语义相同。
6. 删除没有生产调用的 `SearchObjectStoragePort.uploadFile(MultipartFile)` 及对应实现，不创建替代上传流程。其他 Port 不接收 `MultipartFile`。
7. `StorageConfigService`、Settings REST CRUD/连接测试和 `ConfigDrivenStorageAdapter` 内部读取/解密配置仍属于 Capability 内部实现，不做无收益的 package/template 重写。

**实施结果：**

- Capability 已暴露 `StorageRuntimeApi`，只返回 immutable `StorageLocationSnapshot/StorageTemporaryCredential`；配置 Repository、加密密文、`AesUtil` 和 `StorageTokenIssuer` 仍留在 Capability 内部。
- Auth 已改为 `AuthStorageAcl → StorageRuntimeApi`，固定保留 `GET /api/v1/auth/sts`、认证注解及原有八个 JSON 字段；配置缺失与签发失败仍沿用原错误分支。
- Ingestion 已改为 `IngestionStorageAcl → StorageRuntimeApi`。`IngestionStorageTarget` 在单次处理开始时捕获 endpoint/bucket/basePath；每次重新 submit Docling 前重新签发凭据，并同时校验当前 location 与实际签发凭据的 target，变化时 fail-closed。
- `IngestionDoclingAcl` 已包装 `DoclingClient`，把 job/error/failure kind/status/retry-after 映射为 Ingestion 自有 immutable records/exception；submit/get/ack、job 恢复次数、轮询、超时与 Retry-After 顺序未改。
- Knowledge Content 已拥有 `KnowledgeObjectStoragePort`。Library Preview 不再依赖 Search Port；Outbox 图片清理经 `KnowledgeStorageAcl` 获取 prefix 并使用 Knowledge Port，claim/retry/payload/事件类型和 Retrieval 清理顺序未改。
- `ConfigDrivenStorageAdapter` 继续作为同一个底层实现同时实现 Search/Ingestion/Knowledge 三个窄 Port；已删除无生产调用的 `SearchObjectStoragePort.uploadFile(MultipartFile)`，未增加替代上传流程。
- 未修改 Settings CRUD、Storage 连接测试、端点、HTTP/JSON/SSE、前端、数据库、Outbox 表/调度、Docling contract、AAD、AES-GCM envelope、凭据有效期、对象前缀算法或模型调用；205D 当前也已完成。
- 目标测试、`mvn compile` 与 `test-compile` 通过；显式加载 Mockito agent 后完整 `mvn clean test` 通过：484 tests、0 failures、0 errors、16 个无 Docker 的 Testcontainers 用例跳过。

### 205D：Knowledge Content 专用 Outbox 收尾（已完成，2026-07-29）

1. `outbox_event`、`AssetCleanupOutboxRecorder`、`OutboxEventProcessor`、`DELETE_ASSET`、`DELETE_ASSET_GENERATION` 继续由 Knowledge Content 拥有；不抽成独立 bounded context 或全局平台。
2. 保持 Asset 删除/退休与 Outbox insert 的同一 MySQL 事务，保持表、type code、payload、aggregateId、lock token、`SKIP LOCKED`、lease、退避、最大重试、失败状态、清理 cron/时区和 90 天保留语义。
3. Retrieval 删除继续复用 203 的 `KnowledgeRetrievalCleanupAcl`。图片清理由 `KnowledgeStorageAcl/KnowledgeObjectStoragePort` 完成，不再读取 Settings Repository；`IngestionTaskRepository` 与 `IngestionImagePaths` 属于同一 Knowledge Content，可直接协作，不再加跨域 API。
4. 不把 `OutboxEvent` 改名或搬成 persistence envelope；这只是结构偏好，不是本卡的真实跨域问题。206 也不得借拆类改变 claim/retry 行为。

**实施结果：**

- 源码审计确认 Outbox 已完整归属 Knowledge Content：`OutboxEvent`/两类 type、Repository、Mapper/record/XML、`AssetCleanupOutboxRecorder` 与 `OutboxEventProcessor` 均位于 `kb`。Ingestion 直接使用 Recorder、Task Repository 与 `IngestionImagePaths` 是同一 bounded context 内部协作，不新增 API 或 ACL。
- 因生产归属和调用链已经符合任务卡，本卡没有制造生产代码差异，也没有移动/改名 `OutboxEvent`、拆 Processor、增加 Outbox 平台或改数据库。205C 建立的 `KnowledgeStorageAcl/KnowledgeObjectStoragePort` 与 203 建立的 `KnowledgeRetrievalCleanupAcl/RetrievalCleanupApi` 原样复用。
- 补充 Repository 验收，固定 claim 选中后使用同一个 lease token 标记、返回 PROCESSING snapshot，并验证 DONE/RETRY/FAILED 都携带 claim token；现有 MySQL `FOR UPDATE SKIP LOCKED` 并发 claim 测试继续保留。
- 补充 Processor 验收，固定 poll batch/5 分钟 lease、图片成功但 Retrieval 失败后的幂等重试、Asset 全 generation 图片先清理再删 Retrieval、最大重试、90 天 retention、cleanup batch、cron 和 `Asia/Shanghai` 时区。
- 既有 payload golden、Outbox 写入失败向外传播、Asset/Generation 短事务、active generation 不入清理队列、malformed payload 永久失败和 Storage 失败不提前删 Retrieval 的测试继续通过；新增 MySQL 用例验证 Asset 删除与 Outbox insert 同事务回滚。
- 未修改端点、HTTP/JSON/SSE、前端、Flyway、`outbox_event`、Mapper SQL、type code、payload、aggregateId、重试阶梯、最大重试、清理配置、Storage/Retrieval 调用顺序或 generation 语义。
- 目标测试、`mvn compile` 与 `test-compile` 通过；显式加载 Mockito agent 后完整 `mvn clean test` 通过：493 tests、0 failures、0 errors、17 个无 Docker 的 Testcontainers 用例跳过。新增的真实 MySQL 同事务回滚用例也包含在这 17 个环境跳过项中。

### 硬边界与明确不做

- 不修改任何端点路径、HTTP method、认证角色、请求 JSON、响应 JSON、SSE、前端调用或前端类型。固定保留 `/api/v1/activity/**`、`/api/v1/settings/**` 和 `/api/v1/auth/sts`；如实施发现协议必须变化，立即停止并先确认，同时规划 `anchr-web` 修改。
- 不修改数据库、Flyway、`activity_event`/`outbox_event` 表、MyBatis 查询语义、ES mapping、physical index 命名、alias/rebuild 算法、embedding fingerprint 或 generation 流程。
- 不修改模型 prompt、generation/embedding/rerank 参数、Docling contract、加密算法、AAD、凭据有效期、重试/超时、调用顺序或 optional capability 的降级语义。
- 不拆 `CapabilityConfigServiceImpl`、`IngestionTaskProcessorImpl`、`SegmentIndexManagerImpl` 或 `OutboxEventProcessor` 的大方法簇；纯机械拆分类留给 206。
- 不新增 ArchUnit、通用 Event Bus/Command Bus、Mediator、第二张 Outbox 表、统一 `CapabilityPort`、统一 `StorageService` 或覆盖所有 provider 的万能 API。
- 不为了目录对称拆开 `settings + integration`，不复制已有 ConfigDriven Adapter，不重写 Settings Application 与 REST DTO 的同上下文映射。

### 测试与完成条件

- 205A：四类 payload golden、显式 userId、Ingestion after-commit、append 失败不回滚、同步删除仍回滚、Recent limit/cursor/去重/一周窗口、malformed payload、citation anchor/chunks、KB 名称和四个 Activity HTTP contract 全部通过。
- 205B：GENERATION/RERANK 选择、embedding 同 profile、不同 profile deployment、alias 成功激活、部署/激活失败保留旧 serving、cache refresh/invalidate、optional wiring、profile fingerprint/dimension 全部保持。
- 205C：`/auth/sts` 原 JSON/error、Storage location snapshot、target change fail-closed、STS 字段、AAD/envelope、Docling submit/get/ack/retry-after、Preview URL/expiry、对象前缀删除和配置缺失错误全部保持。
- 205D：同事务 Outbox insert、payload golden、claim/lease、并发 claim、幂等重复清理、active generation 防误删、图片/ES 部分失败重试、最大重试、cleanup cron/zone/retention 全部保持。
- 运行各子卡目标测试、`mvn compile`、`test-compile` 和完整 `mvn test`；分别报告通过项、无 Docker 的 Testcontainers 跳过项、环境失败和仓库既有失败。只有 205A–205D 均通过且现有 HTTP golden contract 不变时，ANCHR-205 才标记完成。

**ANCHR-205 总状态：源码与本地回归已完成。** 205A–205D 均已按 provider Application API + caller concrete ACL 或同一 bounded context 内部协作收口；现有 HTTP golden contract 未变。当前机器无 Docker，真实 MySQL `SKIP LOCKED`/同事务回滚、OSS 幂等删除和 Elasticsearch 清理仍需在集成环境验收，不把本地通过误报为生产验收。

---

## ANCHR-206：按已稳定用例边界拆分超大 Application Service

**目标：** 在 202–205 的跨领域边界已经稳定后，只在各自 bounded context 内按真实方法簇降低认知复杂度。206 不再设计领域边界，不再新增跨领域能力，只做能够由现有 characterization/contract tests 证明行为等价的职责迁移。

本卡按当前真实修改面拆为 206A–206F。每个子卡必须独立编译、测试、回滚和验收；一次只执行一张，前一张未完成时不把后一张顺带实现。206 总体涉及 5,359 行主类源码，工作量按 XXL 管理，不再把六条高风险链包装成一次 XL 重构。

### 实施前源码事实

本节以 `dev/clean-up@a766e84e` 为 206 实施前基线：

- `RetrievalQueryServiceImpl` 924 行，同时承担召回编排、generation gate、RRF、rerank、结果映射、父 Asset 聚合、facet/insight 和指标；
- `ConversationServiceImpl` 945 行，同时承担 Session command/query、消息执行与持久化、History query/DTO 映射、cursor、Activity、异步提交和消息 SSE JSON 组装；
- `AgentWorkflowImpl` 921 行，同时承担 decision loop、Action JSON 解析、Tool 调用编排、Evidence finalizer、最终展示生成、trace/progress 和失败收口；
- `IngestionApplicationServiceImpl` 627 行，承担创建/幂等/去重、维护任务、人工重试、查询和 after-commit 调度；
- `IngestionTaskProcessorImpl` 693 行，承担 poll/dispatch、Parse、Embedding、Index 前处理、provider retry/backoff 和失败收口；
- `SegmentIndexManagerImpl` 1,249 行，承担内存生命周期、创建/重建 claim、ES mapping/alias 检查、数据迁移、write barrier、失败回滚和 Capability 激活。

当前已经存在且必须复用的协作者包括：

- Retrieval 的 `SearchKnowledgeAcl`、`QueryEmbeddingService` 和公开 Hit/Page API；
- Conversation 的 `ConversationMessageOrchestrator`、`ConversationMessagePipeline`、`ConversationTurnCodec`、`ConversationRetrievalTraceBuilder`；
- Agent 的 `AgentToolExecutor`、`AgentRunFinalizer`、`AgentTraceRecorder`、`AgentRequestContextResolver`；
- Ingestion 的 `IngestionCreateTransactionRunner`、`IngestionStageTransactionCoordinator`、`IngestionIndexFinalizer` 和 203/205C ACL；
- Index 的 `SegmentIndexAliasManager`、`SegmentIndexWriteBarrier` 和 `RetrievalCapabilityAcl`。

不得为这些已有职责再创建同义的 Port、Manager、Coordinator 或 Facade。`AgentTaskStreamService` 已经是独立的后台任务 SSE 组件，206 不重写它；`OutboxEventProcessor` 的归属和 claim/retry 算法已经由 205D 固定，也不进入 206。

### 206A：Retrieval 查询内部算法拆分（源码与本地回归已完成，2026-07-29）

只处理 `RetrievalQueryServiceImpl`，不同时修改 Search REST、Conversation ACL、Preview、Answer、Follow-up 或索引管理。

1. `RetrievalQueryServiceImpl` 继续实现现有 `RetrievalHitQueryApi/RetrievalPageQueryApi`，保留为用例编排入口。
2. 按当前方法簇提取少量具体协作者：
   - RRF 累积、权重和候选排序；
   - rerank window、provider 调用和分数归一化；
   - `RetrievalHit/TopChunk/Explain` 映射与父 Asset 聚合；
   - facet/insight 组装。
3. generation 批量查询仍经 `SearchKnowledgeAcl`，召回仍经 `SegmentRepository`，预览签名仍经 `SearchObjectStoragePort`；不得再加一层接口。
4. 保持 TEXT/IMAGE 分路召回、RRF 公式、rank constant、alpha/beta、recall topK、rerank window、失败降级、diversify 顺序、limit、聚合键、topChunks、anchor/explain、facet/insight 和全部指标名称/标签/记录时点。
5. 新协作者优先为无 Spring 的 package-private 具体类或纯 Policy；只有确实需要现有 Port 的协作者才作为 Spring bean，不为单一实现新增接口。

**完成条件：** Hit/Page API records 无变化；公共 Search 和 Ask 的结果顺序、分数、预览、citation 输入、explain/facet/insight golden 全部一致；不存在新旧两套融合或 rerank 路径。

**实施结果：**

- `RetrievalQueryServiceImpl` 继续是唯一 Hit/Page 用例入口，保留三路召回、`SearchKnowledgeAcl` scope/generation gate、Repository/Embedding 调用顺序和最终 limit 编排；主类由 924 行降至 305 行，但完成判断不以行数为依据。
- 提取四个同 package、无新增接口的具体协作者：`RetrievalRrfFusionPolicy` 负责 RRF 与每 Asset/SegmentType 前三条 diversify；`RetrievalRerankPolicy` 负责 window、provider、分数融合、指标与原序 fallback；`RetrievalResultAssembler` 负责 Hit/Explain/TopChunk/Preview 和父 Asset 聚合；`RetrievalPageAssembler` 负责 facet/insight。它们不是 Spring Bean，由唯一 Service 直接持有，没有第二条执行路径。
- generation 批量事实仍只经 `SearchKnowledgeAcl`，TEXT/IMAGE 召回仍只经 `SegmentRepository`，DOCUMENT_IMAGE Preview 仍只经可选 `SearchObjectStoragePort`；没有新增 provider API、ACL、Port、Facade 或缓存。
- 固定并验证 RRF 公式和三层 tie-breaker、文本 highlight 优先、三路 vector 标记、diversify 顺序、rerank window/tail、alpha/beta 归一化、无效 provider index、model/empty fallback 指标、原始 TopChunk、Preview fail-open、父 Asset MIXED 聚合、facet 顺序、相关性阈值、CAPTION 不计入既有 hit-source distribution，以及空可见 scope 在 embedding/ES 前短路。
- 现有 `RetrievalQueryTopChunkMappingTest` 已从反射主类私有方法迁到明确协作者；新增完整 Page query characterization，固定 `scope → embedding → text recall → text vector → image vector → generation → rerank` 的调用顺序、route filter 和 Page projection。Search Controller/REST assembler、Conversation Retrieval 相关回归继续通过。
- 未修改 Hit/Page API records、Search/Ask/Preview/Answer/Follow-up 调用方、端点、HTTP/JSON/SSE、前端、ES mapping、RRF/Rerank 参数、generation 规则、预览协议、指标名称/标签或外部调用顺序。
- `test-compile`、206A 目标测试和 Search/Retrieval/Conversation Retrieval 扩展回归通过。沙箱内完整测试因 20 个临时 HTTP Server 无端口权限而得到 503 tests、0 failure、20 environment errors、17 skipped；允许本机端口后相同 `mvn clean test` 通过：503 tests、0 failures、0 errors、17 个无 Docker 的 Testcontainers 用例跳过。当前结果不替代真实 Elasticsearch 召回/相关性和部署验收。

### 206B：Conversation 用例拆分与消息 SSE 传输归位（源码与本地回归已完成，2026-07-29）

只处理 `ConversationServiceImpl` 和 `POST /api/v1/conversations/{sessionId}/messages/stream` 的内部适配，不处理 Agent 决策算法和后台 Agent Task SSE。

1. 按现有方法簇拆出同一 Ask 上下文内的具体协作者：
   - Session create/get/list/rename/delete 与 session cursor；
   - Message execute/persist、自动标题 CAS/touch、Agent Task after-commit submit 和 Activity；
   - History get/list、active task 批量加载与 Turn DTO 映射。
2. `ConversationService` 可以在迁移期保留为 Controller facade，但只能委托；若拆分后只剩无意义转发，则在同一子卡内让 Controller 直接依赖明确用例并删除 facade，禁止两套入口长期并存。
3. `SseEmitter`、异步 executor、断线识别、event name 和 JSON payload 组装迁到 `interfaces.rest` 的具体消息流适配器；Application 继续使用现有 `ConversationProgressListener` 和普通消息执行结果，不新增通用 Event Bus。
4. 固定保留 `trace/delta/citations/done/error` 的名称、顺序、2 KB padding、120 秒 timeout、响应头、断线后继续完成并落库、最终 citation-normalized answer 才对外发送，以及 Runtime Snapshot 发布时点。
5. `AgentTaskStreamService`、`GET /api/v1/agent/tasks/{taskId}/stream`、task/delta/answer_reset/done 协议和 11 分钟 timeout 全部原样保留，只做回归验证。
6. Session keyset cursor、title CAS、updatedAt 单调更新、Turn 事务、Session 删除同步清理、QUESTION best-effort Activity、DTO 字段和指标不得改变。

**完成条件：** Application 不再持有消息 SSE 的 Spring MVC 传输细节；同步消息与流式消息仍共用唯一 Message use case；两条 SSE 和全部 Conversation HTTP golden contract 完全不变。若发现必须修改端点、method、认证、请求/响应 JSON 或 SSE 字段，立即停止并请求确认，同时规划 `anchr-web` 修改。

**实施结果：**

- `ConversationServiceImpl` 保留为唯一 Controller facade，只委托 `ConversationSessionUseCase`、`ConversationMessageUseCase`、`ConversationHistoryQuery` 三个同一 Ask 上下文内的具体用例；主类由 945 行收敛为 77 行，没有保留旧方法体或第二条执行路径。
- Session 用例独立拥有 create/get/list/rename/delete、稳定 keyset cursor 和 DTO 映射；删除事务仍由 facade 的原 `@Transactional(rollbackFor = Exception.class)` 包住，cancel、Session 删除、Agent records 删除和 Activity 同步删除顺序未改。
- Message 用例独立拥有 scope/answer mode 规范化、Orchestrator 调用、Turn/Agent Task 构造、TransactionTemplate 持久化、自动标题 CAS/touch、after-commit submit、QUESTION Activity、response/trace 和指标。同步 HTTP 与消息 SSE 注入并调用同一个 singleton Message use case。
- History 用例独立拥有 get/list、`beforeTurnId`、limit+1、稳定 chronological mapping、PROCESSING task 批量查询、legacy answer status/fallback 恢复和原有分阶段指标；增加小型具体 `ConversationAgentTaskDtoAssembler` 复用 Agent Task DTO 映射，未增加接口或跨域层。
- `ConversationService` 不再暴露 `SseEmitter`。新增 REST 层具体 `ConversationMessageStreamAdapter`，拥有 120 秒 emitter、stream executor、UserContext 传播、断线识别、2 KB trace padding、48 字符 answer chunk、event/payload 组装和 Runtime Snapshot 发布。Controller 仅把原 stream 入口委托给该 adapter。
- `ConversationProgressListener` 只补内部 `onExecutionStarted(turnId, runId)` default callback，让 REST adapter 在 Message use case 读取 Session/执行模型前发送原首个 trace；turn/run ID 前缀、生成次数和后续 Orchestrator 入参保持不变，不建立 Event Bus。
- 固定并验证消息 SSE 的 `trace → delta → citations → done` 顺序、initial trace turnId 与最终落库 Turn 一致、done `sessionUpdatedAt` 取持久化 Session、只发送 citation-normalized 最终答案、断线后业务继续完成。`AgentTaskStreamService`、`GET /api/v1/agent/tasks/{taskId}/stream`、task/delta/answer_reset/done 和 11 分钟 timeout 未修改，只做回归。
- 未修改任何端点路径、HTTP method、认证、请求/响应 JSON、SSE event 名称/字段/顺序、前端、Session/Turn/Task Repository、Mapper SQL、事务时点、模型/Tool 调用、Activity 语义、指标名称/标签或 Agent Workflow。
- `mvn compile`、`test-compile`、Conversation 主流程、Session cursor、History、Message persistence、Conversation Controller SSE 和后台 Agent Task SSE 目标回归通过；允许本机临时端口后完整 `mvn clean test` 通过：503 tests、0 failures、0 errors、17 个无 Docker 的 Testcontainers 用例跳过。真实 MySQL 并发/回滚、部署代理缓冲与浏览器断线恢复仍需集成环境验收。

### 206C：Agent Workflow 内部职责拆分（源码与本地回归已完成，2026-07-29）

只处理 `AgentWorkflowImpl`，不修改 Conversation 持久化、Tool contract、异步 Agent Task 生命周期或 SSE adapter。

1. 复用现有 `AgentToolExecutor/AgentRunFinalizer/AgentTraceRecorder/AgentRequestContextResolver`，不创建同义 executor、finalizer、recorder 或 context service。
2. 只提取三个已经清晰稳定的方法簇：
   - Action JSON/fence/answer type 解析与 protocol error 计数；
   - Evidence finalizer 输入、输出解析和无证据/校验失败结果；
   - 最终 Presentation 生成、stream 完整性校验和 presentation step 记录。
3. decision loop、预算控制、取消检查和 progress/trace 的总体编排继续由 `AgentWorkflowImpl` 持有；等三个方法簇迁移稳定后再评估是否仍有拆 loop 的真实收益，不在本子卡预造第二层 orchestrator。
4. system/finalizer/presentation prompt 必须逐字符保持；模型调用次数和顺序、Tool 顺序、最大 step、READ_LIMIT、protocol retry、evidence 截断、citation marker、fallback 文案、trace JSON、指标和 terminal status 全部保持。
5. 提取类为 Ask 内部具体类；不新增 Agent bounded context、跨域 API、接口层或消息总线。

**完成条件：** 相同模型/Tool fixture 产生相同请求序列、trace steps、progress、citation、answer/fallback 和终态；无重复模型调用、Tool 调用或 trace 写入。

**实施结果：**

- `AgentWorkflowImpl` 继续是唯一 Workflow 入口和 decision loop 编排者，保留预算控制、取消检查、模型决策、Tool 顺序、READ_LIMIT、最终回答校验、终态与 trace finish；主类由 921 行收敛为 618 行，没有第二条 loop 或模型/Tool 执行路径。
- 提取三个同 package、无 Spring 注解、无新增接口的具体协作者：`AgentActionProtocol` 负责 JSON/fence/action/answerType 解析以及连续 protocol error 计数、阈值指标和 reset；`AgentEvidenceFinalizer` 负责证据裁剪与 JSON、最多两次 evidence finalizer 调用、输出校验、usage/trace/progress；`AgentFinalPresentation` 负责最终 stream 调用、完整性校验、answer reset、usage 和 presentation step。
- 三个协作者由唯一 `AgentWorkflowImpl` 构造并直接持有，继续复用原 `ConversationGenerationPort`、`AgentTraceRecorder`、`AgentProperties`、`ObjectMapper` 和 `MeterRegistry`；未新增 Bean、Port、Manager、Facade、消息总线或 Agent bounded context。
- `SYSTEM_PROMPT` 保留在 Workflow；Evidence finalizer 与 Presentation prompt 只移动归属。三个 prompt 均与 206C 前基线逐字节比较且 SHA-256 一致；temperature、max tokens、bounded timeout、模型调用次数和顺序未改。
- 固定并验证 fenced JSON final、Tool calls 顺序和 arguments 编码、protocol retry/fallback 阈值与合法 Tool 后 reset；原有 Workflow characterization 继续覆盖决策事件、模型失败、READ_LIMIT 后 evidence finalization、引用校验、NO_EVIDENCE、两次 protocol fallback、流式 Presentation、citation draft 跳过二次生成、Tool result 截断和取消。
- Agent Workflow、Tool、Trace、Request Context、异步 Agent Task 调度/超时、后台 Task SSE、Conversation Orchestrator、消息用例和消息 SSE 目标回归通过；未修改 Conversation 持久化、Tool contract、异步 Agent Task 生命周期、任何端点、HTTP/JSON/SSE、前端、指标名称/标签或 terminal status。
- `mvn compile`、`test-compile`、目标测试和允许本机临时端口后的完整 `mvn clean test` 通过：506 tests、0 failures、0 errors、17 个无 Docker 的 Testcontainers 用例跳过。真实模型/Tool provider、持久化 trace、取消竞争和部署 SSE 仍需集成环境验收。

### 206D：Ingestion 命令与查询用例拆分（源码与本地回归已完成，2026-07-29）

只处理 `IngestionApplicationServiceImpl`，不修改 worker、阶段执行、Docling、Embedding 或 Retrieval 写入。

1. 按当前入口拆出：
   - 创建、请求规范化、requestHash、clientRequestId replay/conflict 和 dedupe；
   - reparse/reembed 维护任务与人工 retry；
   - task get/list 查询和 limit。
2. `IngestionApplicationService` 在 Controller contract 稳定期间可以保留为薄 facade；不得把同一命令同时留在 facade 和新用例中执行。
3. 继续复用 `IngestionCreateTransactionRunner`，保持 task/items/Asset 创建、overwrite、Outbox insert 的现有事务边界；继续复用唯一 `IngestionTaskProcessor` 做 after-commit submit。
4. 保持 clientRequestId/requestHash、SKIP/OVERWRITE/VERSION、批量上限、状态投影、错误码、Activity after-commit、任务提交时点和现有 REST DTO/JSON。
5. Knowledge Content 内部 Repository 直连是同一 bounded context 协作，不借拆分类新增 Knowledge API/ACL。

**完成条件：** create/replay/conflict/dedupe/maintenance/retry/query fixtures 与迁移前一致；没有重复创建、重复 Outbox、事务内 Activity 或重复 submit。

**实施结果：**

- `IngestionApplicationServiceImpl` 保留为唯一 Controller-facing facade，构造并委托创建、维护/重试、查询三个同 package 具体用例；主类由 627 行收敛为 107 行，不保留旧命令实现或第二条执行路径。
- `IngestionTaskCreateUseCase` 独立拥有请求规范化、50 项上限、sourceType/dedupe 默认值、requestHash、clientRequestId replay/conflict、并发唯一键 winner read、SKIP/OVERWRITE/VERSION、Asset/Item/Task 创建、Activity、KB stats 和 after-commit submit；继续复用唯一 `IngestionCreateTransactionRunner` 的 REQUIRES_NEW write/read。
- `IngestionTaskMaintenanceUseCase` 独立拥有 reparse/reembed、文档悲观锁、下一 target generation 分配、单项/批量 failed retry、summary refresh、状态更新和 after-commit submit；四个原 `@Transactional` 入口继续保留在 Spring facade 上。
- `IngestionTaskQuery` 独立拥有 get/list、默认 20/最大 100 limit、task missing 映射和 creator + exact KB 的 clientRequestId acceptance recovery；归档 KB 的既有 replay/recovery 语义不变。
- 增加无依赖、无 Spring 注解的纯 `IngestionTaskFactory`，由创建与维护用例共享原 Task count/status/finishedAt 投影；四个新类均未新增接口、Port、Manager、Coordinator、Facade 或跨领域 API，Knowledge Content Repository 仍是同一 bounded context 内部协作。
- 固定并验证无 clientRequestId 的每次创建、规范化 replay、payload/KB/order conflict、并发唯一键 winner、非幂等 duplicate 原样抛出、SKIP/OVERWRITE/VERSION、维护任务 projection、retry generation、状态竞争、查询 limit，以及创建和人工 retry 在事务同步存在时只注册一次 after-commit submit。
- 未修改 `IngestionTaskProcessorImpl`、Parse/Embedding/Index 阶段、203/205C ACL、数据库/Mapper、Outbox、任何端点、HTTP/JSON、SSE 或前端；Worker、阶段事务、Index finalizer、Storage/Docling/Retrieval ACL、Repository/Mapper 和 REST Controller 只做扩展回归。
- `mvn compile`、`test-compile`、目标测试和允许本机临时端口后的完整 `mvn clean test` 通过：509 tests、0 failures、0 errors、17 个无 Docker 的 Testcontainers 用例跳过。真实 MySQL REQUIRES_NEW/唯一键竞争/事务回滚、after-commit 调度饱和和部署 Worker 仍需集成环境验收。

### 206E：Ingestion Worker 阶段执行拆分（源码与本地回归已完成，2026-07-30）

只处理 `IngestionTaskProcessorImpl`，必须在 206D 完成并稳定后执行；不修改任务表、状态机或调度模型。

1. poll、restart recovery、local dispatch set、executor rejection 和单 item 总体编排继续由 Processor 持有。
2. 只按真实阶段提取：
   - Parse：source URL、Storage target/credential、Docling submit/poll/ack 和 chunk 映射；
   - Embedding：projection、provider pacing/retry/backoff 和 segment embedding；
   - 失败分类与错误转换。
3. Index 激活继续复用 `IngestionIndexFinalizer`，短事务 transition 继续复用 `IngestionStageTransactionCoordinator`，不得再造 `IndexStageExecutor` 或事务 coordinator。
4. 保持 claim/lease/claimVersion、restart fail、batch/concurrency、Parse timeout/poll、Docling Retry-After、ACK 时点、Embedding 限速/重试、target generation、阶段顺序、失败 Outbox 和 KB stats 刷新。
5. 203 的 `IngestionRetrievalAcl`、205C 的 `IngestionStorageAcl/IngestionDoclingAcl` 原样复用；不得绕过 ACL，也不得增加提供方 API。

**完成条件：** Parse → Embedding → Index 的状态、事务、外部调用和失败序列与迁移前一致；没有双调度、双 ACK、双 embedding、双 index 或 active generation 误清理。

**实施结果：**

- `IngestionTaskProcessorImpl` 继续是唯一 `IngestionTaskProcessor` Bean，保留启动 restart recovery、定时 poll、claim/local dispatch set、executor rejection 恢复、单 item 总体编排和 KB stats 刷新；主类由 693 行收敛为 339 行，没有第二条调度、claim 或阶段执行路径。
- 提取三个同 package、无 Spring 注解、无新增接口的具体协作者：`IngestionParseStage` 负责 source URL、Storage target/credential、Docling submit/poll/recovery/Retry-After/ACK 和 chunk 映射；`IngestionEmbeddingStage` 负责 projection、进程内 pacing、provider retry/backoff 和 segment embedding；`IngestionWorkerFailureClassifier` 负责中断、业务、Embedding 和未知异常的错误码/错误文本转换。
- Processor 继续直接复用 `IngestionStageTransactionCoordinator` 推进 `PARSE → EMBED → INDEX`，通过 `IngestionRetrievalAcl.replaceGeneration` 写入目标 generation，并由唯一 `IngestionIndexFinalizer.activateGeneration` 完成激活；未增加 Index executor、事务 coordinator、提供方 API、ACL、Port、Facade 或消息总线。
- 保持 Docling 终态 ACK 时点、失败 Job 的 retryable ACK、Parse timeout/poll interval、`Retry-After`、Embedding 全局限速和节流重试、TEXT/IMAGE projection、target generation、失败 stage/error/Outbox、索引激活与 KB stats 顺序；Storage、Docling、Retrieval 三个既有 ACL 原样复用。
- 固定并验证成功链调用顺序、Docling transient `Retry-After` 重试、Embedding provider 失败停留在 EMBED 并映射 `EMBEDDING_FAILED`、失败 Job ACK、executor rejection 后可再次调度、同 item 本地去重，以及中断/业务/未知异常和 1000 字符错误截断。
- 未修改任务表、Repository/Mapper、状态机、claim/lease/claimVersion、调度批次/并发、`IngestionStageTransactionCoordinator`、`IngestionIndexFinalizer`、任何端点、HTTP/JSON、SSE 或前端；206D 创建/维护/查询用例和 206F 物理索引生命周期均未改。
- `mvn compile`、`test-compile`、Worker/阶段事务/Index finalizer/ACL/Repository/Mapper/Application 目标回归和允许本机临时端口后的完整 `mvn clean test` 通过：515 tests、0 failures、0 errors、17 个无 Docker 的 Testcontainers 用例跳过。真实 MySQL claim/lease/短事务竞争、OSS 临时凭证、Docling submit/poll/ACK、Embedding provider 限速和 Elasticsearch generation 激活/失败清理仍需集成环境验收。

### 206F：Retrieval 物理索引生命周期拆分

最后执行，只处理 `SegmentIndexManagerImpl`；这是 206 风险最高的子卡，不能与 206A 或 Capability 配置改动合并。

1. `SegmentIndexManagerImpl` 继续持有唯一 `AtomicReference` 状态、operation lock、create/rebuild claim 和公开 `SegmentIndexManager/RetrievalEmbeddingDeploymentApi` 入口。
2. 按当前方法簇提取：
   - mapping/profile 与 alias topology inspection；
   - settings/mapping 加载和物理索引创建；
   - scroll → projection/embedding → bulk write → count validation 的 migration runner；
   - status DTO 组装。
3. 继续复用 `SegmentIndexAliasManager`、`SegmentIndexWriteBarrier` 和 `RetrievalCapabilityAcl`；不得创建第二套 alias manager、状态仓库或 deployment API。
4. 保持单实例内存状态、启动检查、15 秒 topology refresh、物理索引命名、mapping meta、scroll/bulk size、embedding session、限速重试、write barrier、alias 切换顺序、Capability 激活/cache refresh、失败回切与 target index 清理。
5. 不新增持久化 deployment 状态、分布式 lease、增量追平或新索引协议；这些不是机械拆分。

**完成条件：** create/retry/prepare/request/confirm/status 并发 characterization tests 全部保持；真实 Elasticsearch 验证 mapping、全量迁移、count、alias 切换、激活失败回切和失败索引清理。没有真实 ES 验证时只能报告源码与本地回归完成，不能把 206F 标为生产验收完成。

### 统一硬边界

- 不新增或修改跨领域 Application API、ACL、Port、领域状态、数据库字段、Flyway、Mapper SQL、ES mapping 和物理索引协议；202–205 的边界直接复用。
- 不修改任何端点路径、HTTP method、认证角色、请求 JSON、响应 JSON、SSE event 名称/字段/顺序或前端类型。若发现确实必须改变，立即停止并先确认；得到确认后必须同步修改并验证 `anchr-web`。
- 不修改 RRF/Rerank、cursor、generation、Ingestion 状态 transition、模型 prompt、Tool schema/顺序、Outbox claim/retry/cleanup、optional capability 降级或 Storage/Docling contract。
- 不引入 ArchUnit、通用 Event Bus/Command Bus/Mediator、统一 Facade、每类一个接口或只为目录对称存在的空层。
- 不拆 `OutboxEventProcessor`、`AgentTaskStreamService`、`ConversationMessagePipeline`、`IngestionStageTransactionCoordinator`、`IngestionIndexFinalizer`、`SegmentIndexAliasManager` 等已经边界清晰的组件，除非实施时发现可复现缺陷并先另行确认。
- 不以行数、类数量或 package 对称作为验收标准；只以职责唯一、旧实现删除和行为等价作为验收标准。

### 执行顺序与验收

固定顺序：

```text
206A Retrieval
  → 206B Conversation/SSE
  → 206C Agent
  → 206D Ingestion Application
  → 206E Ingestion Worker
  → 206F Segment Index
```

每个子卡均执行：

1. 先补当前方法簇 characterization test，再移动生产实现；
2. 目标测试、`mvn compile`、`test-compile` 和完整 `mvn test`；
3. 对涉及链路复跑现有 HTTP/SSE JSON golden、指标和外部调用顺序验证；
4. 分别报告通过项、无 Docker 的 Testcontainers 跳过项、真实 MySQL/ES/OSS/Docling 未验收项和仓库既有失败；
5. 确认旧方法体、临时兼容构造器和双路径已删除后，才标记该子卡完成。

只有 206A–206F 均完成源码迁移、完整本地回归和各自要求的真实环境验收后，ANCHR-206 才能标记完成。任一子卡发现需要改变业务行为、协议或前序边界时，停止 206，把问题返回对应正确性卡或先请求新的决策。

---

## 明确不立项

### Agent TASK_STAGE attempt 覆盖

不是缺陷。前端明确将同一 stage 的重试合并为一个视觉节点，新 attempt 重启进度、旧 attempt 被忽略。

- [`anchr-web/src/features/ask/agent-activity-model.ts`](../../anchr-web/src/features/ask/agent-activity-model.ts)

如未来需要完整审计，应新增不可变 attempt 日志表，但不能改变当前 UI projection。

### 消息历史分页

`beforeTurnId`、`limit+1`、active Agent task 批量加载和前端 prepend 已正确，不跟随 Session 列表重构。

## 推荐实施批次

### Wave 0：冻结基线

- 记录三个项目提交、工作区状态和现有测试结果；
- 为 Ingestion、搜索、模型切换、Session 建立 characterization/contract fixture；
- 该 Wave 不改业务代码，后续每张卡都以此判断行为回归。

### Wave 1：可并行的低耦合修复

- ANCHR-101A：当前单向量结构下图片分支与向量回写；
- ANCHR-102：HTTP 错误与上传清理契约；
- ANCHR-109：Session keyset 与原子更新。

三张卡修改面独立，可分别开 PR；各自通过后再进入后续依赖。101A 不顺手增加第二向量字段，109 不顺手迁移 Conversation DTO。

### Wave 2：两条 Ingestion 前置分支

可并行：

```text
101A → 104：关闭 embedded-image STS 支线
102  → 103：创建请求幂等与前端恢复
```

104 与 103 都完成后再继续。104 不修改 Docling job 身份；103 的 clientRequestId 不参与 Parse/调度 attempt。

### Wave 3：Docling Parse 协议

- ANCHR-105：Docling parse attempt、稳定指纹和 submit/get/ack。

先发布 Docling 双协议，再发布 app client；本 Wave 不引入调度器和 worker lease。

### Wave 4：持久化执行状态机

- ANCHR-106：execution epoch、stage attempt、lease、artifact 和恢复调度。

必须同时消费 103 的唯一任务和 105 的 Parse 协议；不得重新定义 clientRequestId、parseAttempt 或 ES generation。

### Wave 4B：Ingestion Item 模型收敛

- ANCHR-106B：删除伪执行历史与 Parse artifact，收敛为 task/item 两表当前态。

必须先用 106 的回归测试锁定状态机行为，再迁移物理边界；旧、新 worker 不得混跑。106B 的 normalized schema/API 源码稳定后可以开始 107 的源码开发和隔离测试，但 107 不得进入生产发布或接管真实 `INDEX` 流量，直到 106B 在业务 MySQL 上处理失败 history、完成 V18 停机迁移，并通过 lease/retry/artifact、公开投影和部署稳定观察。该 Wave 不增加 Asset generation，也不改变前端任务协议。

### Wave 5：Asset Segment 写入一致性

- ANCHR-107：Asset indexGeneration、目标 generation 清理重写、可见性和 Outbox 清理。

该 Wave 只接管 `INDEX` stage，不创建 physical index version。107 的生产发布必须显式记录所依赖的 106B 数据库验收证据，不能用 107 自身测试替代该门禁。

### Wave 6：单向量 Profile 投影契约

- ANCHR-101B：单 `embedding`、统一 Projection Policy、唯一向量字段和输入来源指标。

使用隔离索引验证：文本请求和图片请求使用同一个多模态模型配置，查询和写入都只访问 `embedding` 字段；保留 107 的 generation/ID/可见性规则。本 Wave 不新增向量字段、不切换生产 alias。

### Wave 7：Embedding Profile 部署

- ANCHR-101C：内存目标 profile、全程 JVM 写屏障、physical index rebuild、alias 切换后启用配置。

只有 101B Projection Policy/唯一向量字段契约和 107 change log 都稳定后才能执行生产重建。

### Wave 8：文档内嵌图片检索

- ANCHR-110：图片 artifact/bbox、`DOCUMENT_IMAGE`、图片对象随 Asset generation 清理、同字段召回分路、父文档聚合和 Preview。

先完成 Docling/app 契约和存量 reparse 影子验证，再开放后端召回与前端 hit type。110 不新增第二向量字段，不绕开 101C 直接切换物理索引。

### Wave 9–14：DDD 与架构治理，按真实协作链推进

```text
201 领域地图、状态所有权与交互决策（文档）
 → 202 仅建立后续会用到的最小 Query/Command/Event contract
 → 203 Knowledge Content ↔ Retrieval
 → 204 Ask ↔ Knowledge Content / Retrieval
 → 205A Activity ─┐
   205B Capability ├→ 206 超大 Service 机械拆分
   205C 专用 Outbox ┘
```

201 可以基于当前 clean-up 快照立即评审，不增加依赖或生产类。202 不追求 DTO/import 全量清零，只给 203–205 的真实调用铺路。203 优先解决 ES 写入位于 MySQL 事务内和 Repository 交叉使用的问题；204 随后统一普通 Ask 与 Agent Tool 的 scope/evidence 边界。205A/B/C 可拆成独立 PR，但都必须保留 203/204 的公开能力和现有兼容行为。206 最后执行，不在正确性 PR 中顺手搬包、补齐四层模板或拆大类。

### 总关键路径

```text
dev/clean-up@4dfa6b31 源码快照
 → 201 → 202 → 203 → 204 → 205A/205B/205C → 206

106B/107 的 Item/generation/Outbox 语义 ───────→ 203/205C
101C 的 profile 部署语义 ─────────────────────→ 203/205B
109 的 Session CAS 语义 ───────────────────────→ 204
```

## 跨项目验证矩阵

| 场景 | app | web | docling |
|---|---|---|---|
| IMAGE + 文本模型 OCR embedding（101A） | 分支输入、单元、ES 集成 | 图片/OCR 搜索回归 | OCR fixture |
| IMAGE + 多模态模型视觉 embedding（101A） | 每 Asset 一次调用、单载体回写、无重复 KNN 候选、失败传播 | 图片视觉搜索回归 | 原图输入与多 chunk 契约 |
| 单 embedding Profile 投影（101B） | Ingestion/Rebuild 使用同一规则、文本和图片请求共用同一个 `modelName/dimensions`、唯一向量字段、BM25/RRF 基线不变 | 文本/OCR/图片结果回归 | 不涉及 |
| 多模态 → 纯文本切换（101C） | active/target profile、全程停写重建、alias、延后启用 | 重建期间查询可用、索引写入明确不可用 | 不涉及 |
| 同维度不同模型切换 | fingerprint 门禁、禁止跨空间查询 | 相关性回归 | 不涉及 |
| 无 OCR 图片切换影响 | 覆盖率报告、TEXT_VECTOR_UNAVAILABLE | 降级确认展示（如产品提供） | OCR 重试 fixture |
| 上传响应丢失 | 幂等创建、恢复查询 | placeholder 恢复、OSS 保留 | 不涉及 |
| Docling 同 parse attempt 重试 | contract test | 不涉及 | 指纹、409、重启 |
| app 重启恢复 | lease、claim、stage recovery | 轮询状态保持 | job 存在/丢失 |
| Ingestion Item 模型收敛（106B） | normalized execution/artifact、窄查询、状态投影、旧 execution 隔离 | 现有 stage/progress/retry 展示回归 | submit/get/ack 合同不变 |
| ES partial bulk/DB rollback | generation、ID、outbox、对账 | 搜索无重复 | 不涉及 |
| PDF 内嵌图片 artifact/bbox（110） | DTO/域对象/Segment 逐字段一致、稳定 ID、manifest | 不涉及 | Picture Item provenance、bbox 坐标系、上传幂等 |
| Markdown 外链图片无布局坐标（110） | nullable page/bbox、索引成功、对象生命周期 | Preview 降级无伪高亮 | URL allowlist、大小限制、无 bbox contract |
| 文本查询命中文档内图片（110） | 同字段分路 KNN、RRF、多样化、父 Asset 聚合 | DOCUMENT_IMAGE 筛选、缩略图、父文档 Preview | 图片 fixture 与共享空间 contract |
| DOCUMENT_IMAGE 模型切换（110） | 按 segmentType 投影、存量 reparse、alias/回滚 | 能力降级说明 | Docling job 结果可恢复、OCR/caption fixture |
| 内嵌图片删除/重解析（110） | item 稳定图片目录、既有清理事件、对账 | 无陈旧缩略图 | 同 attempt object key 幂等 |
| 500 个会话 | keyset、同时间戳 | 自动/手动加载 | 不涉及 |
| 重命名与消息并发 | CAS、时间单调 | 标题不回退 | 不涉及 |
| Knowledge Content → Retrieval generation 写入（203） | ES 写入位于 DB 事务外；激活前二次 fence；失败 generation 不可见且可清理 | 搜索结果不出现未激活 generation | 不改变 Parse contract |
| Ask → Knowledge/Retrieval 查询（204） | 普通 Ask 与 Agent Tool 共享 scope/evidence contract | 两条 SSE、citation、Preview 与任务恢复回归 | 不涉及 |
| Activity/Capability/Outbox 支撑边界（205） | Activity 不回滚主事务；profile 失败保留旧 serving；清理幂等重放 | Recent 与配置页面协议不变 | Docling/Storage capability contract 不变 |

## 完成定义

每张任务卡只有同时满足以下条件才算完成：

1. 根因对应测试先失败、修复后通过。
2. 现有成功 API 和 DTO 保持兼容，新增字段默认可选。
3. 前端 type check、目标 lint、目标测试、production build 分开报告。
4. app 目标 Maven 测试通过；MySQL/ES 用例单独报告 Testcontainers 结果。
5. Docling Python 测试和跨语言 JSON contract fixture 通过。
6. 覆盖网络超时、服务重启、重复请求、并发和部分成功故障注入。
7. 明确区分通过项、环境阻塞和仓库既有失败。
8. 不修改与任务无关的现有工作区变更。
9. ANCHR-101B 必须验证 Ingestion/Rebuild 对文本、OCR、原图使用相同的输入规则；多模态文本和图片请求使用同一个 `modelName/dimensions`；ES 只有一个 `embedding` 字段；既有 BM25/RRF/Rerank 顺序不变。不得增加第二向量字段或承担 110 的业务召回配额。
10. ANCHR-101C 必须验证 profile fingerprint、同维不同模型、重建全程写阻塞、alias 切换后才启用目标配置以及失败时旧配置不变。
11. ANCHR-106B 必须在真实 MySQL 上验证 fresh→V18 与 normalized V15→V18 两条路径、17 列 item、无 ingestion CHECK/FK、窄查询、坏 ownership/旧 execution 不可领取、公开投影兼容和部署稳定性；不得改变 106 的 retry/lease/stale-worker 业务结果。
12. ANCHR-107 必须验证 generation 激活门禁、部分 bulk 后目标 generation 清理重写、Outbox 清理失败与重试，不承担 101C 的 physical index 验收。
13. ANCHR-110 必须验证图片 bbox 来自 Picture Item provenance、私有对象签名访问、存量 reparse、对象生命周期、同字段分路召回、父文档聚合和 Preview；不得以 `chunks[].bboxes` 猜测图片位置或增加第二 dense 字段。
