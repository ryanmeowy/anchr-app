# Anchr 三项目根因修复任务卡

## 文档范围

本文最初基于三个项目下列 `main` HEAD 对真实调用链进行审查；各任务卡的“当前流程与根因”保留该审查基线，“实施与验证记录”则按后续开发工作区的实际实现与验证持续更新。

- `anchr-app`: `02031f9b35966ec98200c3ca0dcd9649cd941bfb`
- `anchr-web`: `35a09a4d5955d336884aa034ad16935268c2a0b8`
- `anchr-docling`: `c36eeb69d899728a803242d03fc93ae3b64bd490`

> 初始审查不把当时的未提交变更视为 `main` 实现；本文当前 ANCHR-106B 的实施记录来自 `anchr-app` 的 `dev/clean-up` 工作区，同样不表示已经合并到 `main` 或发布。

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
| ANCHR-201 | 建立架构适应度测试与依赖边界 | 待执行 | P1 | M | app | 101A–101C、102–107、109–110 稳定后 |
| ANCHR-202 | REST DTO 与 SSE 传输协议退出 Application | 待执行 | P1 | XL | app | 201 |
| ANCHR-203 | 用模块 Port 取代跨模块 Infrastructure 依赖 | 待执行 | P1 | L | app | 201、101B/101C、105–110 |
| ANCHR-204 | 选择性富领域化与聚合边界收口 | 待执行 | P1 | L | app | 106B、107、109、202、203、205 |
| ANCHR-205 | Outbox 从 KB Domain 拆为可靠消息模块 | 待执行 | P2 | M | app | 107、201、203 |
| ANCHR-206 | 按用例拆分超大 Application Service | 待执行 | P2 | XL | app | 202–205 |

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
| 105 | `parseAttempt/sourceRevision/doclingRequestId/doclingJobId` 与 Docling 指纹协议 | 104 关闭后的请求体 | worker lease、阶段调度、parse artifact 生命周期、ES generation |
| 106 | `executionEpoch/stageAttempt/lease/nextActionAt` 与可恢复阶段调度 | 105 的 submit/get/ack 和 parse attempt | 创建请求幂等、Docling 指纹、Asset generation、ES alias |
| 106B | `ingestion_task_item`、current execution、artifact registry 的物理边界；内部 phase 与公开投影的唯一映射；按用例收窄查询模型；从 phase-local `stageAttempt` 到 execution-global `claimVersion` 的内部持久化表示；artifact 登记事务与 post-commit ACK 时序 | 106 已确定的 stage/retry/lease/attempt/artifact 语义和 stale-worker 拒绝结果 | 改变任务行为或 stale-worker 结果、Docling ACK HTTP 幂等协议、Asset generation、通用 DDD/Service 拆分 |
| 107 | Asset `indexGeneration`、目标 generation 重写、MySQL/ES 可见性和清理事件 | 106B 的 execution/artifact 边界与 106 的 INDEX stage；现有 outbox 能力 | physical index version、embedding profile、检索融合、Outbox 搬包 |
| 109 | Session 列表 keyset cursor、title CAS、updatedAt 单调更新 | 现有消息/Agent 数据 | 消息历史分页、Agent Activity、Conversation DTO 分层 |
| 110 | `EmbeddedImageArtifact` 契约、`DOCUMENT_IMAGE` Segment、图片对象随 Asset generation 清理、同字段分路召回、父文档聚合和图片命中预览 | 104 的默认关闭门禁；105/106 的 Parse artifact；106B 的 artifact registry；107 的 generation/ID/事件；101B 的单向量 Policy；101C 的 profile 部署 | 图片专用生命周期、第二向量字段、模型部署状态机、通用 Docling attempt、通用 Outbox 搬包 |
| 201 | ArchUnit/依赖图/违规基线 | 所有已稳定代码 | 移类、加 Port、修改业务行为 |
| 202 | REST DTO 与 SSE 适配边界 | 201 的规则 | 领域聚合、跨模块 Port 全面治理、按用例拆大类 |
| 203 | 剩余跨模块 Infrastructure 依赖的 Port/Adapter 迁移 | 101B–107、109–110 已确定的能力契约 | 重定义业务协议、改变状态机、拆分大 Service |
| 204 | 将 106/106B/107/109 已稳定规则收口为领域行为 | 正确性卡的状态、CAS、持久化边界和 generation 语义 | 新增状态/字段/API、改变调度和检索结果 |
| 205 | Outbox 技术模型的模块归属 | 107 已使用的发布/消费语义 | 修改表语义、重试/backoff、事件业务触发条件 |
| 206 | 稳定边界内的机械职责拆分 | 202–205 的最终边界 | 新业务规则、协议/mapping/schema 修改、相关性调参 |

### 依赖交付契约

- 101B 向 101C 交付：按 profile 选择 `TEXT/IMAGE` 输入的单向量 Projection Policy 和唯一向量字段契约；101C 不复制输入选择规则。
- 101B 向 110 交付：按 profile 对 `TEXT/IMAGE` 输入生成同一 `embedding` 字段的 Projection Policy；110 只增加内嵌图片制品和同字段召回分路，不复制投影算法、不增加第二向量字段。
- 104/105/106 向 110 交付：默认关闭的旧上传门禁、稳定 Parse attempt 和持久化 Parse artifact；110 以版本化图片制品契约重新启用支线，不恢复旧 CBC/裸 URL 协议。
- 107 向 110 交付：Asset generation、目标 generation 重写、激活门禁和旧 generation 清理事件；110 不建立第二套图片 generation。
- 101C 向 110 交付：目标 profile 的安全重建能力；110 的 mapping/存量回填复用该流程，不直接切 alias。
- 105 向 106 交付：幂等的 `submit/get/ack` 和 parse attempt 标识；106 只决定何时调用。
- 106 向 106B 交付：已经验收的阶段、重试、lease、fence、Docling 恢复和公开 DTO 行为；106B 只重排持久化边界与读模型，不重新定义这些行为。
- 106B 向 107 交付：收敛后的 current execution、通用 artifact registry 和进入 `INDEX` phase 的 fenced context；107 只增加 generation、目标 generation 重写与索引激活一致性。
- 106B 向 110 交付：artifact registry 的表结构、登记 API 和事务语义；110 把版本化 `EmbeddedImageArtifact` 保存在既有 `PARSE_RESULT.images[]`，不新增 artifact type、业务表或 `ingestion_task_item` 图片制品指针列。
- 102 向 103/105/106 交付：通用 HTTP 错误信封；各业务卡只定义自己的 errorCode、retryable 和 accepted 语义。
- 201 只建立守门规则；202–205 分别消除自己拥有的违规；206 最后在不改变行为的前提下拆分类。

### 共享修改面与强制顺序

| 共享修改面 | 可能涉及的卡 | 强制顺序 | 后卡必须保留的前卡契约 |
|---|---|---|---|
| Ingestion create/processor / 后续 stage handler | 101A、103、104、105、106、106B、107、101B、101C、110 | 101A → 104 → 105；102 → 103；两路汇合 → 106 → 106B → 107 → 101B → 101C → 110 | 图片分支/向量回写；创建幂等；关闭 STS 支线；Parse 协议；可恢复状态机；normalized execution/artifact 边界；Asset generation/目标重写；单向量 Policy；profile 部署；内嵌图片投影 |
| `Segment` / `SegmentDocument` / mapping / bulk writer | 107、101B、101C、110 | 107 → 101B → 101C → 110 | Asset generation/目标重写 → 单向量投影契约 → 物理索引部署 → 内嵌图片 schema/回填 |
| `UnifiedSearchServiceImpl` / ES repository | 107、101B、110、206 | 107 → 101B → 110 → 206 | active generation 过滤 → 同一模型与单向量字段 → 图片分路召回/父资产聚合 → 机械拆分 |
| Search/Conversation result DTO 与 Preview | 110、202、206 | 110 → 202 → 206 | 图片命中与父文档预览语义 → DTO 边界迁移 → 机械拆分 |
| Capability settings / `SegmentIndexManagerImpl` | 101C、203、206 | 101C → 203 → 206 | 延后启用与单实例重建 → 依赖倒置 → 机械拆分 |
| `ConversationServiceImpl` / Session repository | 109、202、204、206 | 109 → 202 → 204 → 206 | keyset/CAS 结果 → DTO 边界 → 领域方法 → 机械拆分 |
| Outbox publisher/processor | 107、205、206 | 107 → 205 → 206 | 事件产生语义 → 模块迁移 → 机械拆分 |

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
- alias 切换后由 `ServingEmbeddingConfigActivator` 执行现有 `capability_config.select/disableAll` 并刷新本地缓存；激活异常会切回旧 alias。
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
- 每个 item 覆盖生效后的 `fileName`，以及 `title/fileType/mimeType/sizeBytes/objectKey/fileHash/sourceUrl`。
- 空白字符串、文件类型大小写和 URL 文件名回退规则与 Asset 创建逻辑保持一致。
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

UPLOAD 和 URL 两条创建链路统一使用同一协议：

1. 用户每次主动发起新导入时生成新的 UUID，并建立 `import-create:<clientRequestId>` placeholder。
2. POST 前同步持久化完整、可重放且不含 STS 凭据的请求体；上传请求持久化最终 `objectKey/fileHash`，URL 请求持久化 URL item。
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
4. 独立 IMAGE 文件继续通过 `sourceUrl` OCR。
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
- `sourceRevision` 优先使用文件 hash，其次使用 objectKey、原始 sourceUrl、assetId，并以 `v1:<sha256>` 落库。每次重新生成的 OSS 签名 URL 不参与 revision。
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
stage_attempt
stage_retry_count
stage_started_at
next_action_at
lease_token
lease_until
parse_request_snapshot
parse_result_object_key
```

`docling_request_id/docling_job_id/source_revision/parse_attempt` 直接消费 ANCHR-105 的字段和语义，本卡不得重复定义或改名。

保留现有 `stage/status/progress/errorCode/errorMessage` 作为前端投影，内部状态机为：

```text
PARSE_SUBMIT → PARSE_WAIT → PARSE_PERSIST → EMBED → INDEX → COMPLETE
      │              │            │           │        │
      └──────────────┴────────────┴───────────┴────────┴─→ FAILED
```

实现中不存储虚构的 `RETRY_WAIT` stage。一次可重试失败会保留当前 `execution_stage`，写入未来的 `next_action_at` 并释放 lease；到期后重新 claim 同一 stage。跨 stage 成功迁移时 `stage_retry_count` 清零。

定时调度器每轮只扫描数据库中到期且无有效 lease 的 item。候选先提交到有界 executor，worker 再在短事务中用 `SELECT ... FOR UPDATE SKIP LOCKED` claim；因此 executor `AbortPolicy` 拒绝时数据库尚未领取，下一轮仍可恢复。`afterCommit submit()` 只保留为低延迟 wake-up hint，不再承担可靠性。

claim 使用数据库时间生成 `lease_until`，并原子执行：

- `stage_attempt + 1`；
- 生成新的随机 `lease_token`；
- 首次进入 stage 时固定 `stage_started_at`；
- PENDING 投影为 RUNNING，并刷新 task summary；
- 接管过期 lease 时增加 `stage_retry_count`。

所有迁移都携带 `itemId + taskId + kbId + executionEpoch + expectedExecutionStage + expectedStageAttempt + leaseToken + RUNNING` fence，并完整写入下一状态。lease 到期本身不让当前 worker 立即失效；只有新 worker 接管并改变 token/attempt 后，旧 worker 才无法提交。这避免了“外部调用刚返回、仅因时钟越过 lease 就丢结果”的窗口，同时仍能阻止 stale worker 覆盖。

本地 `locallyDispatchedItems` 只去重“尚未开始 DB claim”的 executor 提交；claim 完成即释放。即使旧线程卡在 provider 调用，同一 JVM 也能在 lease 过期后再次 dispatch 和接管。

旧的无 fence 写接口 `prepareParseAttempt/recordDoclingJob/markItemRunning/markItemSuccess/markItemFailed` 已从 Repository、Mapper 和 XML 删除，运行链路不能再绕过 execution fence。显式从 `EMBED/INDEX` 创建内部任务时只校验可恢复所需的 Parse artifact，禁止构造无法恢复的非法起点。

#### 重试预算

`stage_retry_count` 是当前内部 stage 的统一恢复预算：

- Docling/OSS/embedding 的临时失败、ACK 临时失败、job 404、可重试 terminal job 和过期 lease 接管会增加；
- `PARSE_WAIT` 的 queued/running 正常轮询不增加；
- 404、ACK 后重提和内部恢复不增加 `parse_attempt`，仍复用同一业务解析身份；
- 成功进入下一内部 stage 后清零；
- 默认 `stage-max-retries=5` 表示最多 5 次恢复，随后 fenced FAILED；
- `embedding-rate-limit-max-attempts` 保持“provider 总调用次数”语义，值为 N 时只允许 N-1 次持久化重试，不能多出第 N+1 次调用；429 优先读取 `OpenAiException.statusCode`，消息匹配只作为兼容兜底。

用户显式 retry 是新的业务执行：`execution_epoch + 1`，同时按 ANCHR-105 将 `parse_attempt + 1`、生成新的 `docling_request_id`，并清空旧 snapshot、job、lease 和 Parse artifact 引用。内部恢复只增加 `stage_attempt/stage_retry_count`，不能冒充新的 parse attempt。

#### Parse 调度与恢复

消费 ANCHR-105 交付的 `submitJob/getJob/ackJob`，移除 Processor 对旧 `DoclingClient.parse()` 长轮询 facade 的依赖，不在 Java 线程中 sleep/poll。成功结果先压缩写入：

```text
ingestion/{taskId}/{itemId}/parse/{parseAttempt}/jobs/{jobId}/parse-result.v1.json.gz
```

一次 worker 只执行一次 submit/get 或一个本地阶段；queued/running 写入下一次轮询时间后立即释放线程。`parse-stage-timeout` 以首次进入当前等待阶段的 `stage_started_at` 计算，正常轮询和同 stage retry 不会重置。

首次 submit 前持久化 secret-free `parse_request_snapshot`，包含 contractVersion、fileName、options 和稳定 OSS 目标，但不包含签名 URL、STS 密文或过期时间。每次调用时重新生成：

- 有 objectKey：使用当前签名下载 URL；
- 无 objectKey 的 URL 资产：回退到 `asset.sourceUrl`，再回退到 item sourceUrl；
- 旧的 embedded-image OSS 凭据仍受 ANCHR-104 默认关闭门禁保护，不能因 106 被重新启用。

当 ACK、TTL、容量淘汰或边车重启使 Docling 内存幂等记录消失时，状态机必须从数据库读取并复用 ANCHR-105 已持久化的 `parse_attempt/docling_request_id/source_revision` 和稳定解析参数重提；不得在恢复过程中静默生成新 parse attempt 或改变 sourceRevision。

可重试 terminal job 会先 ACK，再以同一 v2 身份返回 `PARSE_SUBMIT`；ACK 失败则保留原 jobId 和身份，在同 stage 重试。`anchr-docling` 已增加成功和失败终态的合同测试，确认 DELETE 同时清除 request 映射，此后相同 `requestId + fingerprint` 会创建不同的新 jobId。

#### Durable Parse artifact 与内存向量交接

Parse 成功后先以 OSS 原子 create-only 写入 versioned gzip JSON artifact，再把 object key 通过 fence 写入 item，最后 best-effort ACK。stale worker 若失去 fence，不得 ACK winner 仍可能需要的唯一结果。

EMBED 从 Parse artifact 恢复并在内存中生成 chunk 向量。模型向量不写 OSS、不写 MySQL 指针，也不进入 artifact registry。正常成功路径以 fenced `EMBED → INDEX` 迁移保留当前 `lease_token/lease_until/claim_version`，随后在同一 worker 中把内存 chunks 直接交给 `IngestionIndexFinalizer`，因此绝大多数文件只调用一次嵌入模型。

若进程在完成 phase 交接后、INDEX 提交前退出，租约到期后恢复 worker 会从持久化的 Parse artifact 重新映射 chunks 并重新调用嵌入模型，再执行 INDEX。该额外调用只发生在真实恢复场景，避免为了极低概率的 INDEX 中断持续保存所有文件的高维向量。`retainLease` 只允许用于 `EMBED → INDEX`，Repository 会拒绝其他 phase 携带该标记。

同一 Asset 的跨 task generation、跨 execution 目标 generation 清理重写和 ES 可见性不由 Parse artifact 或内存交接冒充解决，仍归 ANCHR-107。

#### MySQL 原子边界

普通 stage 迁移和 Asset parse/index 投影通过 `IngestionStageTransactionCoordinator` 放在同一个短 MySQL 事务；任一写入异常时 item、task summary 和 Asset 一起回滚。INDEX 仍沿用现有 `IngestionIndexFinalizer`：先锁 item claim，再锁 Asset，并在事务内调用 ES bulk。该做法能 fence stale item worker，但不能让 ES 随 MySQL 回滚，明确留给 107 的 generation/outbox 修复。

### 边界

本卡拥有任务从到期领取到各 stage 完成的持久化调度、fencing 和恢复；不拥有创建请求去重（103）、Parse 协议幂等（105）、ES generation/可见性（107）或 physical index alias（101C）。进入 `INDEX` 时只把内存 chunks、Parse artifact 恢复入口、execution epoch 和 lease context 交给 107，不自行定义 segmentId 或激活 generation。

以下仍是 ANCHR-107 的未解决窗口，不能把 106 标成跨存储一致性完成：

- ES bulk 仍发生在 MySQL 事务/行锁期间，ES 成功后 MySQL 回滚无法撤销；
- 不同 task 同时处理同一 Asset 时还没有 Asset generation fence，旧 task 可能覆盖新 task 的 Asset 状态；
- mapper 的普通 segmentId 在 INDEX 恢复重算时可能变化，恢复前必须清理同一未激活 target generation，不能直接追加；
- overwrite cleanup 仍是 COMPLETE 后 best-effort 删除，进程退出可能跳过；
- partial bulk、active/target generation、目标 generation 清理重写、激活门禁和清理 outbox 全部归 107。

Docling `images[]` 已完整保存在 parse artifact，供 110 消费；`DOCUMENT_IMAGE` Segment、内嵌图片对象生命周期、图片召回和 Preview 不在 106 展开。前端不读取内部 execution/lease/artifact 字段。

### 兼容与验收

前端继续按 taskId 轮询，现有 stage/progress/errorCode 不变，浏览器不参与任务推进。

- `anchr-web` 无生产代码改动：现有轮询只消费公开 task/item 投影，内部状态增加不改变 DTO。
- `anchr-docling` 无生产代码改动：直接消费 105 的单次 submit/get/ack；本卡只补 ACK 后同身份重提合同测试。
- REPARSE/REEMBED 继续保持旧 Processor 的真实行为：都从源文件重新 Parse；没有 parse artifact 的 fresh REEMBED 不允许误从内部 EMBED 起步。
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
5. 验证 claim backlog、过期 lease、stage retry、Parse artifact 写入、EMBED→INDEX 租约交接和 Docling queue 指标后再恢复正常流量。

106B 的 V18 只接收 normalized V15 schema，不提供从旧 V10/legacy-only 状态直接推断并回填 execution history 的路径；这类更老数据库若要保留数据，仍必须另行制定并验证数据搬迁方案。当前平台仍须遵守既有的“同一套 object storage 配置承载所有 Asset”约束；运行中的 storage endpoint/bucket 切换会让原文件和 checkpoint 一并不可读。

### 实施与验证记录

- `anchr-app`：合并后的 V1 基线、DB claim/lease/fence、阶段调度器、Parse artifact、内存向量交接、INDEX 恢复时重算、原子 Asset 投影、AbortPolicy、无 fence 旧接口删除均已实现。
- 单元/合同测试覆盖 executor 拒绝、同 JVM lease 接管、正常轮询不耗重试、404/ACK 恢复、稳定 snapshot、artifact-before-ACK、stale worker 禁止 ACK、正常链路单次 embedding、INDEX 恢复重算、interrupt、URL source、图片兼容、非法 stage 起点和事务 rollback。
- MySQL Testcontainers 测试已编写：DB-time lease、过期接管、stale token、显式 retry epoch reset、item/task summary 在 Asset 投影异常时回滚；本机无 Docker，相关用例会跳过，必须由有 Docker 或真实 MySQL 的 CI 执行。
- OSS create-only、摘要和大小限制已由 adapter/artifact 单测覆盖；读取时 SHA-256 metadata 缺失、格式非法或不匹配均拒绝。artifact 编码改为流式 JSON → GZIP，并在原始 JSON 和压缩输出两层执行上限，避免先分配完整未压缩 JSON。真实 OSS 的条件写和权限仍需部署环境 smoke。
- `DoclingClient` 的成功响应也改为有界流读取，`app.docling.max-response-bytes` 默认 256 MiB；错误响应只读取前 4 KiB，防止边车异常响应先完整进入 JVM 堆。
- 106 实施轮当时的本地回归：`anchr-app` 全量 408 项，0 failure、0 error、23 skipped；23 项均因当前环境无 Docker 而跳过的 Testcontainers 测试。`anchr-docling` 全量 20 项及 10 个 subtests 通过，改动文件 Ruff `--no-cache` 通过。`anchr-web` ingestion/background recovery 相关 23 项通过；106B 的最新 app 结果单独记录在下节。
- 本卡“已完成”表示源码实现和可执行的本地验证完成，不表示已提交、执行数据库初始化或发布；也不表示 ANCHR-107 已完成。

---

## ANCHR-106B：收敛 Ingestion Item 执行模型与持久化边界

**目标：** 在不改变 ANCHR-106 已验收行为和前端协议的前提下，拆开任务项业务事实、当前执行、外部解析 attempt、制品登记和公开读模型，停止继续扩张 `ingestion_task_item`。

### 根因

旧实现把 `ingestion_task_item` 从最初的 18 列扩展到 33 列，当前 `IngestionTaskItem` 还混入了查询时 join 得到的 `taskCreatedBy`，合计承载 34 个属性。迁移压平后这些兼容列直接存在于 V1，但职责问题没有因此消失。它同时负责：

1. item/asset 身份和来源快照；
2. 去重决策与最终业务结果；
3. Docling parse attempt 和稳定请求身份；
4. worker phase、重试、调度时间、lease 和 fencing；
5. Parse artifact 指针；
6. 前端使用的 `stage/status/progress/error` 投影。

这不是单纯的“列数多”，而是多组独立生命周期被迫在同一行同步更新：

- `execution_stage/stage/status/progress` 同时描述状态，claim、成功和失败迁移都必须维持组合一致；
- `COMPLETE/FAILED` 既存在于 `execution_stage`，又存在于 `status`，而 terminal execution stage 还会丢失实际失败 phase，只能依赖额外的公开 `stage` 保存；
- `execution_epoch/parse_attempt` 当前在显式 retry 中同步递增，但前者属于 ingestion execution，后者属于 Docling 幂等身份，语义不应靠“永远相等”维持；
- `stage_attempt` 和 `lease_token` 都参与 claim fencing，但一个同时被用作 artifact producer 路径，命名和职责不清；
- `kb_id` 可由 `task_id -> ingestion_task.kb_id` 得到；`dedupe_strategy` 又是一次创建请求的 task 级参数，却在每个 item 重复保存。

热路径也被宽表拖累：

- `selectClaimableItemForUpdate` 在持有行锁时读取包含 JSON、TEXT、artifact key 和所有公开字段的完整 33 列；
- claim 更新后又读取一次完整行并刷新 task summary；
- list/get/retry 查询同样装载 lease、snapshot、artifact 等内部字段，最终 REST DTO 只使用其中一部分；
- `parse_request_snapshot`、`source_url`、`error_message` 和 artifact key 的大小不会直接等同于 InnoDB 行内大小，但会扩大物化、映射、网络和 Buffer Pool 压力。真实物理收益必须通过 MySQL `EXPLAIN ANALYZE`、`information_schema` 和样本长度分布验证，不能只按 DDL 最大长度估算。

源码：

- [`IngestionTaskItem.java`](../src/main/java/com/anchr/core/ingestion/domain/model/IngestionTaskItem.java)
- [`IngestionTaskItemRecord.java`](../src/main/java/com/anchr/core/ingestion/infrastructure/persistence/IngestionTaskItemRecord.java)
- [`IngestionTaskMapper.xml`](../src/main/resources/mapper/ingestion/IngestionTaskMapper.xml)
- [`V1__create_biz_tables.sql`](../src/main/resources/db/migration/V1__create_biz_tables.sql)
- [`IngestionTaskProcessorImpl.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionTaskProcessorImpl.java)

### 修复方案与实施结果

#### 1. 收敛运行时职责，并用前向迁移落到最终物理结构

新代码已经把 `ingestion_task_item` 的职责收敛为稳定 item、当前 execution 指针和公开兼容投影：

```text
id / task_id / current_execution_id
asset_id
file_name / file_hash / source_url
stage / status / progress
dedupe_result / duplicate_asset_id
error_code / error_message
created_at / updated_at / finished_at
```

- `file_name/file_hash/source_url` 是创建请求和历史展示的来源快照，不随 Asset 后续修改或软删而变化。
- `dedupe_strategy` 的新 source of truth 已移到 `ingestion_task`；Application 在创建 task 前拒绝同一 task 内 strategy 不一致的数据。
- 授权和作用域读取统一从 parent task 取得 `kb_id`；V18 物理删除 item 上重复的 `kb_id/dedupe_strategy`，新写入和 retry 不再双读兼容列。
- V18 删除 item 上的 parse/execution/lease/snapshot/artifact 兼容列、三个旧 claim/kb 索引和两个由单列主键覆盖的复合唯一索引；最终 `ingestion_task_item` 恰好保留上面的 17 列。
- 状态值域、计数范围、terminal 时间、lease 配对、允许迁移图和 artifact producer/digest 等规则由 Repository/Application 在写入前校验；数据库不建立业务 CHECK。
- item → current execution → parse attempt 的归属由创建、候选、claim、renew、transition、retry 和 INDEX 前锁定查询中的 ownership join/CAS 维护，不建立外键。V18 还会条件清理曾短暂落到开发库中的 ingestion CHECK/FK 残留。
- `embedding_result_object_key` 既不被运行时生成、读取或双写，也会在真实存在该历史列时由 V18 条件删除；没有该列的 fresh schema 走同一迁移。

#### 2. Parse attempt 与 execution 分开建模

V13 新增 `ingestion_item_parse_attempt`，保存 Docling 幂等身份：

```text
id / item_id / attempt_no / status
request_id / job_id / source_revision / request_snapshot
created_at / updated_at / finished_at
```

V14 新增 `ingestion_item_execution`，保存一次 ingestion 执行：

```text
id / item_id / execution_epoch
execution_kind / execution_status / phase
parse_attempt_id
claim_version / phase_retry_count
phase_started_at / next_action_at
lease_token / lease_until
error_code / error_message
created_at / updated_at / finished_at
```

- `execution_kind` 明确区分 `INITIAL/REPARSE/REEMBED/EXPLICIT_RETRY`。新写入由 Application 显式传递执行意图，不再仅按 `sourceType` 猜测；因此通用 create 即使收到兼容的 `REPARSE/REEMBED/RETRY` source 值仍记为 `INITIAL`，专用维护端点才写 `REPARSE/REEMBED`。
- `phase` 只保存 `PARSE_SUBMIT/PARSE_WAIT/PARSE_PERSIST/EMBED/INDEX` 等真实位置；`SUCCEEDED/FAILED` 由 `execution_status` 表达，失败 execution 不再丢失真实失败 phase。
- `claim_version` 是 execution 全周期单调 `BIGINT/long` fence，跨 phase 不清零；`lease_token` 只表示当前 holder。候选和更新 SQL 都防止 `BIGINT` 溢出。
- 显式 retry 在同一事务内创建新的 parse attempt 和 execution，再 CAS 切换 `current_execution_id`；CAS 失败抛异常并回滚，不能提交孤儿历史。
- Ingestion normalized 表之间不建立外键；`current_execution_id` 只保留普通复合索引。item、parse attempt、execution 和 artifact 的归属关系由 Repository 的 join、锁、事务和 CAS 更新维护。
- V18 不推断或回填旧 execution/parse attempt；它只允许在 V13–V15 normalized 数据已经完整的库上收缩兼容列。迁移前的 ownership/retryable/active 对账是发布门禁，不得靠旧列兜底。

#### 3. 制品使用 registry，并校验真实存储字节

V15 新增 `ingestion_item_artifact`：

```text
execution_id / artifact_type
artifact_version / provenance
producer_claim_version
object_key / content_sha256
created_at
```

- 主键 `(execution_id, artifact_type)` 保证一个 execution 的同类 winner 唯一；应用层当前只允许 `PARSE_RESULT`，模型向量明确不属于持久化 artifact。
- 新产物 `PRODUCED` 必须同时有 producer claim 和 SHA-256；这些规则由应用层在登记前校验。
- SHA-256 针对 OSS 中实际保存的 gzip bytes；create-only replay 会读取并摘要已有对象，而不是摘要本次重新序列化的字节。
- claimed worker 会加载完整的 Parse `IngestionArtifactReference`。读取 `PRODUCED` Parse artifact 时先校验 provenance、producer claim 不晚于当前 claim、registry digest，再解压和校验业务 identity；合法 gzip 但被替换的对象、未来 claim 产物或 producer 元数据漂移都会被拒绝。
- EMBED 生成的向量只存在于 worker 内存并在同一 lease 下直接交给 INDEX。若该 worker 丢失，INDEX recovery 读取 Parse artifact 并重新嵌入；registry 不登记 `EMBEDDING_RESULT`。
- transition、parse-attempt、item 公开投影、artifact registry 和 task summary 在同一 MySQL 事务提交。106B 不改变 Docling ACK HTTP 协议和幂等规则，但拥有“artifact 的 MySQL 提交完成后才能 ACK”的本地事务时序；Parse artifact ACK 通过 `afterCommit` 执行，stale/冲突/回滚均不能提前 ACK。
- stale worker 仍可能留下 immutable orphan OSS object，但不能登记或替换 winner；对象清理策略不在本卡扩成生命周期系统。

本卡不替 107 定义 `indexGeneration`，也不把 ES 写入伪装成 ingestion artifact。

#### 4. 保留公开列，但由唯一 Policy 维护

现有前端直接消费 `stage/status/progress`，而 REEMBED 与 retry 存在不能只按 current phase 无损重算的兼容值，因此本轮选择保留 item 公开投影列，不在迁移时重算历史：

| 事件 | 公开投影 |
|---|---|
| UPLOAD 创建 | `UPLOAD / PENDING / 0` |
| URL 创建 | `PARSE / PENDING / 10` |
| 专用 reparse 端点创建 | `PARSE / PENDING / 20` |
| 专用 reembed 端点创建 | `EMBED / PENDING / 60` |
| 通用 create API 传 REPARSE/REEMBED/RETRY | 保持旧行为 `UPLOAD / PENDING / 0` |
| 首次/后续 Parse claim | `PARSE / RUNNING / max(当前值, 20)` |
| Embed claim/transition | `EMBED / RUNNING / max(当前值, 55)` |
| Index claim/transition | `INDEX / RUNNING / max(当前值, 75)` |
| 显式 retry | `UPLOAD / PENDING / 0`，首次 claim 后为 `PARSE / RUNNING / 20` |
| 成功 / SKIP | `ASKABLE / SUCCESS或SKIPPED / 100` |
| phase 失败 | 保留真实 phase 对应 stage，progress 不倒退 |

`IngestionPublicProjectionPolicy` 是唯一 phase/source → 公开值映射。Application 创建、Repository claim/retry、Processor transition/failure 和 Index finalizer 都只消费该 Policy 的结果；Repository 会在写 execution 前校验 transition 的 phase/outcome 与公开投影，条件更新还拒绝 progress 倒退。stage/status/progress 的业务值域不下沉为数据库 CHECK。为了保持 REEMBED 等既有前端进度不倒退，active 投影仍显式使用当前公开 progress 做单调上界，这属于兼容语义，不伪装成可仅凭 phase 无损重算。

#### 5. 查询按用例收窄

- `IngestionItemViewRecord`：list/get/REST 只读公开字段，不 join execution/parse-attempt，也不读取 lease、snapshot、artifact 或 claim fence。
- `ClaimCandidateRecord`：加锁扫描只读 item id/progress、current execution id/epoch/phase、claim version 和旧 lease token，并要求 execution 引用同属 item 的 parse attempt；坏 ownership 不会反复进入调度。
- `ClaimedExecutionRecord`：只为成功 claim 的 worker 装载实际使用的 source、TaskContext、公开 progress、execution fence、parse attempt 和 artifact reference；不再读取 file name/hash、公开 stage/status、error TEXT 或 terminal 展示时间。非 Parse phase 用 SQL `NULL` 分支避免物化 `request_snapshot`。
- `FailedItemRetryRecord`：单项与批量 retry 只读 status、current execution/epoch/status、parse attempt 和 source revision，并且必须命中同属 item 的 `FAILED execution + parse attempt`。`UNSUPPORTED_FILE_TYPE` 属于 Asset 创建前的不可恢复预检失败，没有 Asset/execution，普通 retry 必须返回 409，不能伪造 attempt/execution 后再必然失败为 `DOCUMENT_NOT_FOUND`；若未来要支持，需另建“重新提交文件并重新预检”的入口。
- `IngestionTaskItemRecord` 不再同时承担 public、claim、retry 和 worker 映射。现有 `IngestionTaskItem` Java 类只保留为 application compatibility carrier，不再对应一张物理宽表；进一步把 application carrier 拆成富聚合属于 ANCHR-204，不能在本卡顺手重写整个 Processor。

### 迁移与发布

当前开发库已经有成功的 V1/V2/V3/V7/V13–V15 Flyway history，106B 不再改写这些已执行文件，而是新增前向迁移 `V18__contract_ingestion_item_storage.sql`。V16/V17 曾在开发历史中出现后被撤销，V18 刻意不复用这两个版本号。

V18 只做物理收敛，不做数据推断或业务回填：

1. 条件删除 `ingestion_task_item`、`ingestion_item_parse_attempt`、`ingestion_item_execution` 上遗留的 CHECK 和外键；
2. 删除 item 的 16 个重复/内部兼容列及旧 claim/kb 索引；
3. 删除 parse-attempt/execution 上由单列主键覆盖的 `(id,item_id)` 复合唯一索引；
4. 若真实存在 `embedding_result_object_key` 历史列则条件删除，不存在则跳过；
5. 保留 V13–V15 normalized rows、current execution 指针、公开 item 投影和 artifact history。

适用路径分两类：

- fresh database：按 V1/V2/V3/V7/V13–V15/V18 顺序初始化，最终直接得到 17 列 item；
- 已到 V15 的数据库：只有在 active/retryable item 都有同属 normalized execution/parse attempt、current pointer 和 artifact 无 orphan，且旧 worker 已停止时，才允许原地执行 V18。

当前本机开发库的只读预检结果是：16 个 item、17 个 parse attempt、17 个 execution、0 个 artifact；active item 无缺失 execution，current/parse/artifact ownership 无 orphan；item 旧 request/job/source/snapshot/parse/embedding 指针均未承载事实。旧 item phase/epoch/attempt 与 normalized 表已出现漂移，反而证明这些兼容列不能继续作为 source of truth。

本机服务在资源变更自动重启时曾尝试旧版 V18，并留下 `success=0` 的 Flyway 记录；第一条 ALTER 被数据库中残留的 CHECK/FK 原子拒绝，业务表列、索引和数据均未改变。修正版 V18 已在独立 MySQL 8.4 临时库中同时验证“无历史 embedding/约束”和“有历史 embedding + CHECK/FK”两条 V15→V18 路径。业务库仍不得自动重试：必须先停 app，核对失败记录与表结构，再对这条已确认根因的 V18 做明确 repair/重跑；禁止用 repair 掩盖其他 checksum 或缺失 migration。

发布顺序：

1. 停止所有旧 app/worker 和开发热重载，备份并重复执行 normalized 数据对账；
2. 确认失败 V18 没有留下部分 DDL，再只处理该失败记录；
3. 用修正版 V18 完成迁移，核对 17 列、目标索引、无 ingestion CHECK/FK 及 normalized 行数；
4. 启动只使用 normalized model 的 app，验证 backlog、lease takeover、retry、artifact、task summary 和公开 DTO；
5. 完成 `EXPLAIN ANALYZE`、锁等待和部署观察后，才能解除 107 的生产门禁。

V18 不是旧/新 worker 可混跑的 expand-contract 迁移；MySQL DDL 也不能视为整份脚本可事务回滚。未停旧实例、未通过对账或失败历史未处理时均禁止执行。

### 边界

本卡是 106 的持久化与模型收敛延续，只能保持或重表达 106 已确定的行为：

- 不修改 Docling 指纹、ACK HTTP 幂等协议和 parse attempt 规则，这些属于 105；artifact 是否已经在 MySQL 提交，以及只能在提交后触发 ACK 的事务时序属于 106B；
- 不修改 retry/backoff/lease/timeout/stale-worker 的业务结果，这些属于 106；106B 可以把内部 fence 从 phase-local `stageAttempt` 重表达为 execution-global `claimVersion`，但相同 stale worker 必须继续得到与 106 一致的拒绝结果；
- 不增加 `indexGeneration`、目标 generation 清理重写、ES 可见性或 outbox，这些属于 107；
- 不定义图片 artifact 内容、图片 Segment 和多模态召回，这些属于 110；
- 不借机迁移整个模块的 DTO、Port、聚合或拆分大 Service；通用 DDD 治理仍属于 201–206。

106B 可以引入完成自身 schema/read-model 收敛所需的最小 record、repository method 和 projector，但不得把 `IngestionTaskProcessorImpl` 的全量职责拆分伪装成本卡交付。

### 验收

- 创建/幂等恢复、clientRequestId 查询、单项/批量 retry、REPARSE/REEMBED 均已有 REST characterization；其中四个变更入口的 POST 路径、ADMIN/USER 权限、路径参数透传与返回 DTO 合同已锁定。Ingestion 不存在 SSE 协议，任何批准的公开投影修正应单独列明。
- app 在 `PARSE_WAIT/EMBED/INDEX` 重启恢复、多实例 lease 接管、stale worker fencing 和 Docling ACK 恢复行为与 106 一致。
- normalized model 上线后的显式 retry 创建新 execution，旧 execution 不再被 claim，新产生的 parse attempt/Parse artifact 历史可审计。
- list/get 不读取 lease、snapshot、artifact 内部字段；claim candidate 不读取来源 TEXT、错误 TEXT、公开 dedupe 字段。
- Parse phase 之外不物化 `parse_request_snapshot`；REST DTO 永不装载该 JSON。
- artifact registry 的唯一键和应用层 fence 能阻止 stale worker 登记 winner artifact；新 execution 只能登记带完整 digest/producer 的 `PRODUCED` artifact。
- fresh schema 与带 normalized 数据、artifact、历史 embedding 列和残留 CHECK/FK 的 V15 schema 都能迁移到相同 V18 契约；在 V18 检查点 item 恰好 17 列、三张 ingestion 表无 CHECK/FK、current pointer 和 normalized history 不变。后续 V19 由 ANCHR-107 单独增加 `target_index_generation`，不回写为 106B 的字段。
- 在真实 MySQL 上对 claim、task list、failed retry 执行 `EXPLAIN ANALYZE` 并记录扫描行、锁等待和延迟；没有这些证据不得声称性能改善。
- V18 已删除旧 item claim/kb 索引并保留 `PRIMARY/current_execution/task/asset` 四组索引；是否需要新增 task-scoped covering index 只能由真实执行计划决定。
- 即使前端无生产代码修改，部署验收仍必须重跑并通过现有 ingestion/background recovery 轮询测试。

### 实施与验证记录

- `anchr-app` 源码已收口：V13–V15 normalized model、V18 物理收缩、V18 检查点的 17 列 item、窄读模型、公开投影 Policy、显式 execution intent/retry、全链路 ownership join、应用层计数/lease/transition 校验，以及 Parse artifact digest/provenance/producer fence 均已接入。Embedding 向量仍只在同 lease 内存交给 INDEX，恢复时从 Parse artifact 重算。ANCHR-107 的 V19 在该检查点之后增加第 18 个稳定业务列 `target_index_generation`。
- 当前业务库已做只读数据对账，normalized 行和 ownership 满足 V18 前置条件；但热重载曾留下失败的 V18 history。失败耗时 325 ms，第一条 ALTER 未生效，列、索引和数据仍为迁移前状态；根因是库中仍有源码已经删除的 ingestion CHECK/FK。修正版已覆盖该真实 schema 差异，但本轮没有 repair 或迁移业务库。
- 使用项目同一组 Flyway migration 和 MySQL 8.4，本机创建了两个隔离临时库并真实执行 V15→V18：一条为 fresh/no-legacy 分支，另一条带代表性数据、current pointer、历史 embedding 列和残留 CHECK/FK。两条都迁移成功，17 列/目标索引/无 CHECK-FK/normalized 数据保留断言通过，临时库已清理。
- 同样的两条迁移路径已固化为 Testcontainers 回归：fresh 契约在 `IngestionExecutionStateMysqlIntegrationTest`，带数据/制品/残留约束升级在 `IngestionItemStorageMigrationMysqlIntegrationTest`。本机没有 `/var/run/docker.sock`，所以 14 个 ingestion MySQL 用例只确认编译和被 Surefire 发现，不能冒充容器实跑通过。
- JDK 21 全量回归：431 项，0 failure、0 error、27 skipped；27 项全部是当前机器无 Docker 而跳过的 6 个 Testcontainers 类。当前运行环境禁止 Mockito 自附加 agent，因此最终命令显式使用项目解析出的 Byte Buddy 1.17.8 `-javaagent`。Repository/Mapper/Artifact 的新增门禁测试包含 task/item 数值、lease 双向配对、非法 transition、不可恢复 preflight retry 拒绝、cross-item/current-claim ownership、legacy/future artifact 拒绝。
- `git diff --check`、Mapper `xmllint`、主/测试代码编译和非 Testcontainers 定向测试通过。未挂显式 agent 的沙箱测试会因 Mockito attach 权限失败，不是业务断言失败；最终全量命令已在允许本地测试端口的环境中通过。
- 尚未执行业务库 repair/V18、真实 workload `EXPLAIN ANALYZE`、部署后 backlog/锁等待观察、真实 OSS smoke，因此不声称性能已改善或可上线。
- `anchr-web` 与 `anchr-docling` 没有 106B 生产代码改动；本轮没有重跑前端 ingestion/background recovery 测试。App 的 create/recovery、retry/reparse/reembed REST 合同测试均已补齐。
- 本卡当前处于“源码与迁移实现完成、独立 MySQL 迁移验证完成；业务库失败历史处理、停机迁移和部署验收待完成”的状态；不表示已 stage、commit、repair、迁移业务库或发布。

---

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

overwrite 必须在同一事务 soft delete 旧 asset 并写 `DELETE_ASSET` outbox，不再直接调用 ES 或吞异常。复用现有 outbox 的 claim、lease、backoff 和 `SKIP LOCKED`。

同一事务还要发布可重放的索引变化记录：

```text
eventId / revision
assetId
operation = GENERATION_ACTIVATED | ASSET_DELETED
indexGeneration
occurredAt
```

该变化记录服务于 Asset/ES 一致性对账和 generation 事件重放。ANCHR-101C 当前采用全程停写重建，不消费该日志，也不记录 watermark。

### 边界

本卡中的 `indexGeneration` 是单个 Asset 内容/segment 的逻辑版本，不是 ES 物理索引版本。本卡拥有目标 generation 清理重写、generation 激活门禁、MySQL/ES 可见性和变化事件；可以在所有召回路由之后统一过滤非 active generation，但不得改变路由分数或相对顺序。不创建/切换 read-write alias，不选择 embedding profile，不增加第二向量检索路由，也不搬迁 Outbox 模块。Outbox 的现有可靠投递机制在本卡只被消费，包结构治理归 205。

### 验收

- bulk 部分失败后重试不产生重复 segment。
- ES 成功、DB 失败时新 generation 不可见。
- ES 写完后 app 崩溃可幂等恢复。
- overwrite 删除失败进入 outbox。
- 删除与 ingestion 并发不会复活资产。
- generation 激活/删除变化可按 revision 幂等重放，供一致性对账和后续明确立项的消费者使用。

### 实施与验证记录

- V19 增加 `asset.active_index_generation`、`ingestion_task_item.target_index_generation` 和只追加的 `asset_index_change`；没有新增业务 CHECK 或外键。旧 Asset/旧 ES 文档兼容为 generation 0。
- 新建 Asset 固定从 generation 1 开始；REPARSE/REEMBED 在 Asset 行锁内按 `max(active generation, 已分配 target generation) + 1` 分配，旧数据中 target 为空的 item 在首次 claim 时用相同规则补齐。target 只保存在稳定 item，不复制到 execution。
- `DoclingChunkMapper` 使用既有 `IdGen` 为每个 Segment 生成普通 segmentId；`SegmentBulkWriter` 直接使用相同值作为 ES `_id` 写入，设置 `refresh=wait_for` 保证激活前新 generation 已可搜索，并拒绝空 ID、响应数量不一致和任一部分失败。
- INDEX finalizer 先校验当前 claim 并锁定 Asset，再清理同一未激活 target generation 的重试残留、bulk 覆盖写、CAS 激活 generation，最后在同一 MySQL 事务写变化记录、旧 generation 清理 outbox 和 item COMPLETE。数据库提交失败时新 generation 留在 ES 但不满足 active gate；后续同 target 重试会先清掉残留。
- 搜索在 RRF 合并后、Rerank 前一次批量读取候选 Asset 的 active generation，按原顺序 fail-closed 过滤；全文读取在分页开始时固定同一个 active generation。generation 0 查询同时兼容显式 `0` 和旧文档缺字段。
- Segment Preview 与刷新入口也校验父 Asset 的 active generation；旧 generation、已删除 Asset 或不存在的 Segment 统一返回 `SEGMENT_NOT_FOUND`，不会通过旧 segmentId 绕过搜索可见性门禁。
- 普通删除与 overwrite 都在 Asset 行锁事务内 soft delete，并同时追加 `ASSET_DELETED` 变化和 `DELETE_ASSET` outbox；旧 generation 使用 `DELETE_ASSET_GENERATION` 复用现有 claim、lease、backoff 和失败重试，不再直接删 ES 或吞异常。
- JDK 21 全量回归共 463 项，0 failure、0 error、27 skipped；27 项均为当前机器无 Docker 而跳过的 Testcontainers 用例。`git diff --check`、三个变更 Mapper 的 `xmllint`、ES mapping JSON 校验、主代码编译和测试代码编译通过。
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

**状态：** 主体源码与本地回归已完成；“Docling 已上传但 app 尚未登记 Parse artifact”的终态失败窗口已通过 attempt 独占目录和既有 Outbox 补偿；真实 OSS/Elasticsearch、存量 PDF/Markdown reparse、101C physical index 发布及部署验收待完成。功能开关继续保持默认关闭；本任务不新增业务表迁移。

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
6. 当前图片对象 key 包含 Parse requestId；重新解析会产生新对象，因此必须以既有 `PARSE_RESULT.images[]` 作为删除、overwrite 和 reparse 的对象清单，不能依赖 ES Segment 反查。
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
- [`UnifiedSearchServiceImpl.java`](../src/main/java/com/anchr/core/search/application/impl/UnifiedSearchServiceImpl.java)
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

`EmbeddedImageArtifact` 作为现有 `PARSE_RESULT` artifact 中 `ParseResponse.images[]` 的一部分持久化、校验和恢复；不再复制第二份图片 manifest artifact，也不增加图片专用业务表。

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

图片对象 key 直接保存在现有 `PARSE_RESULT` artifact 的 `images[]` 中，并通过 execution → ingestion item 的既有关系归属 `assetId + targetIndexGeneration`。新 generation 激活时由已有 `DELETE_ASSET_GENERATION` 事件同时删除旧 generation 的图片对象和 Segment；Asset 删除复用已有 `DELETE_ASSET` 事件。清理失败沿用同一个 outbox 事件重试，不新增图片状态表、专用事件或第二套 generation 生命周期，也不只依赖 ES `_source` 作为对象删除清单。

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
2. app 在现有 `PARSE_RESULT` artifact 中持久化 `EmbeddedImageArtifact`，新增 `DOCUMENT_IMAGE` Segment 类型并复用 `sourceRef` 和唯一 `embedding` 字段；搜索仍不暴露新 hit type。
3. 通过 101C 创建目标 physical index，并对存量 PDF/Markdown 执行可恢复 reparse/backfill；禁止只 scroll 旧 ES。
4. 影子验证图片数量、bbox 覆盖率、Parse artifact 中的对象 key、向量维度、父资产聚合和相关性，再切 alias。
5. 后端开启图片召回 route，web 最后开放筛选、缩略图和 Preview；旧客户端忽略新增可选字段仍可使用。
6. 通过 107 已有 Asset/generation 可靠事件同步清理 Segment 和图片对象；保留既有 outbox 失败与重试统计。

### 边界

本卡唯一拥有 `PARSE_RESULT.images[]` 的内嵌图片 schema、`DOCUMENT_IMAGE` Segment、既有 Asset/generation 清理事件中的图片对象删除、同一向量字段上的图片召回预算、Rerank 多模态公平性、父文档聚合和图片命中 Preview。它不新增图片专用生命周期表、状态或第二 dense 字段，不重定义 101B Projection Policy，不重建 101C profile 状态机，不复制 107 generation 语义，也不实现多模态回答生成或通用 DDD 搬包。唯一新增的 `DELETE_INGESTION_ATTEMPT_ARTIFACTS` 只是失败 attempt 的 OSS 补偿事件，复用现有 Outbox 表和重试器，不表示图片拥有独立生命周期。

### 验收

- PDF 中一个图片跨多个/混合 chunk 时只产生一个 `DOCUMENT_IMAGE`；bbox 精确来自该 Picture Item，不包含同 chunk 正文/其他图片 bbox。
- PDF 图片存在 provenance 时，Docling JSON → Java DTO → Ingestion 域对象 → ES Segment 的 pageNo、bbox、coordOrigin、宽高逐字段一致。
- Markdown 外链图片无 bbox 时解析、索引和检索成功；Preview 明确降级而不是伪造坐标或整份文档失败。
- 私有 bucket 不依赖裸 URL；embedding 和图片 preview 都由 app 根据图片 Segment 的 `sourceRef` 生成有期限、用途受限的签名输入。
- 同 parse attempt 重试、app 重启、部分 ES bulk 和 generation 重跑不产生重复 Segment/OSS 对象。
- 已登记 `PARSE_RESULT` 的 reparse、overwrite 和 Asset 删除复用原有 generation/Asset 事件清理图片与 Parse artifact；终态失败 attempt 通过既有 Outbox 删除独占图片/Parse 目录，失败沿用同一重试器。
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
- 图片列表只保存在既有 immutable `PARSE_RESULT` artifact，不复制第二份图片清单。新请求使用 attempt 独占图片目录和显式 key-layout 标记，旧请求继续兼容原 key；PDF/Markdown 即使没有文本 chunk，只要存在有效 `DOCUMENT_IMAGE` 也可继续索引。
- `DELETE_ASSET_GENERATION` / `DELETE_ASSET` 先删除图片对象/目录和 ES Segment，再删除整个 Parse attempt 目录及对应 artifact registry 行。终态失败 transition 在同一 MySQL 事务写 `DELETE_INGESTION_ATTEMPT_ARTIFACTS`，只清理自己的 attempt 目录；未新增图片表或图片状态。
- 检索已拆为 BM25、普通同字段 vector route、`DOCUMENT_IMAGE` 同字段 vector route，分别配置 topK/similarity，RRF 后按 `assetId + segmentType` 限流再 Rerank、父资产聚合；`resultType` 保持 `TEXT/IMAGE/MIXED`，`topChunks` 保留 `DOCUMENT_IMAGE`、page/bbox、命中来源和短期图片缩略 URL。
- Preview 保持父 PDF/Markdown `previewUrl` 与 page/bbox 定位，另签发 `imagePreviewUrl/imagePreviewExpiresAt`；Preview cache key 已包含对象身份。Web 已增加文档图片筛选、标签、缩略图和预览侧栏，回答链路仍只消费文本代理，没有暗中开启多模态 Answer。
- 本地验证：`anchr-docling` 27 tests + 10 subtests 全通过，改动文件 Ruff 全通过；`anchr-app` 全量 530 tests 为 0 failure/0 error，27 个环境型 Testcontainers 用例按既有条件跳过；`anchr-web` 74 项测试全通过且生产构建通过。三仓 `git diff --check` 通过。
- 尚未执行真实私有 OSS 上传/签名/按前缀删除 smoke、真实 Elasticsearch mapping/KNN/相关性与多实例故障演练，也未批量 reparse 存量文档或执行 101C alias 发布。因此当前状态不表示已 stage、commit、开启 feature flag 或发布。

---

## DDD 与架构治理任务

以下任务不替代 ANCHR-101A–101C、102–107、109–110 的正确性修复。它们负责把已经稳定的业务规则收口到明确的模块和领域边界中，避免新复杂度继续堆积在少数 Application Service。项目继续保持模块化单体，不拆微服务，也不对 settings、Dashboard、Token 等简单 CRUD 强行套富领域模型。

## ANCHR-201：建立架构适应度测试与依赖边界

**目标：** 把当前口头约定变成 CI 可执行规则，阻止依赖继续反向扩散。

### 当前根因

源码目录已经按业务模块和 `interfaces/application/domain/infrastructure` 分层，但没有自动化约束。目前可确认：

- Conversation、Search、KB、Settings 的 Application 广泛导入 REST DTO；
- Ingestion 和 Search Application 直接导入 Infrastructure 实现；
- Domain 中存在 Spring `StringUtils` 和 `MultipartFile`；
- 不存在 ArchUnit 规则，新的反向依赖不会被 CI 阻止。

典型源码：

- [`ConversationService.java`](../src/main/java/com/anchr/core/conversation/application/ConversationService.java)
- [`SearchObjectStoragePort.java`](../src/main/java/com/anchr/core/search/domain/port/SearchObjectStoragePort.java)
- [`IngestionIndexFinalizer.java`](../src/main/java/com/anchr/core/ingestion/application/impl/IngestionIndexFinalizer.java)

### 修复方案

1. 引入 `archunit-junit5`，建立以下硬规则：
   - Domain 不得依赖 Spring Web、interfaces、application、infrastructure；
   - Application 不得依赖 interfaces 和 infrastructure；
   - Interfaces 只能通过 Application API 进入业务；
   - 一个模块不得直接依赖另一模块的 infrastructure；
   - integration/infrastructure 可以实现 Domain/Application Port，反向禁止。
2. 对现存违规建立显式、逐文件的迁移清单；规则必须禁止新增违规，每完成后续任务就删除对应豁免。
3. 输出模块依赖图并在 CI 中验证无循环。
4. 本任务不移动业务类、不改变 Spring Bean，不与正确性修复混在同一 PR。

### 边界

本卡只建立规则、依赖图和逐文件违规基线。发现违规时必须登记到 202–205 或对应正确性卡，不能为了让 ArchUnit 立即全绿而在本卡移动类、创建空壳 Port、扩大 ignore package 或修改业务流程。

### 验收

- 新增任何 Application → REST DTO 或 Domain → Spring Web 依赖时 CI 失败。
- 当前违规清单有负责人和对应任务，不使用宽泛包级忽略。
- 模块依赖图无循环。
- 业务测试结果和运行时 Bean 数量不变。

---

## ANCHR-202：REST DTO 与 SSE 传输协议退出 Application

**目标：** 让 Application 表达业务用例，而不是 HTTP/SSE 协议。

### 当前根因

`ConversationService` 的参数和返回值全部是 `interfaces.rest.dto`，并直接返回 Spring `SseEmitter`。`UnifiedSearchService`、KB、Settings 等 Application API 也直接暴露 REST DTO。依赖方向实际变成：

```text
interfaces → application → interfaces
```

这让 Web 协议、序列化字段和业务用例无法独立演进。

### 修复方案

按模块引入 Application Command/Query/Result，不直接复用 REST DTO：

```text
REST RequestDTO → RestAssembler → Application Command
Application Result → RestAssembler → REST ResponseDTO
```

Conversation 优先拆分为：

- `CreateConversationCommand`
- `SendMessageCommand`
- `ListConversationSessionsQuery`
- `ConversationMessageResult`
- `ConversationHistoryResult`

SSE 边界调整为：

```text
ConversationController
→ ConversationStreamAdapter（持有 SseEmitter）
→ ConversationMessageUseCase
→ ConversationProgressListener（纯 Application 事件）
```

Application 不再导入 `SseEmitter`，但 SSE 的事件名、顺序和 payload 保持 `trace/delta/citations/done/error` 兼容。

Search 使用 `SearchCommand/SearchPageResult`；REST 层负责 DTO 与枚举/日期/cursor 的协议转换。KB 和 Settings 按调用复杂度逐步迁移，不一次性重写所有接口。

### 边界

本卡只改变 HTTP/SSE 传输对象与 Application 用例对象之间的适配位置。它可以增加 assembler、command/result 和 stream adapter，但不改变 URL/payload/event 顺序，不设计领域聚合（204），不治理跨模块 Infrastructure 依赖（203），也不把一个大 Service 按业务职责全面拆开（206）。

### 兼容与验收

- HTTP URL、method、JSON 字段和 SSE 事件完全不变。
- Controller contract test 对迁移前后响应做 golden comparison。
- Application 包不再导入 `interfaces.rest.dto` 或 `SseEmitter`。
- CLI、后台任务或测试可以直接调用用例接口而不构造 REST DTO。

---

## ANCHR-203：用模块 Port 取代跨模块 Infrastructure 依赖

**目标：** 保持模块化单体，但让跨模块协作通过明确能力契约完成。

### 当前根因

Ingestion Application 同时依赖 KB Repository、Settings Repository、Docling Client、Parser Mapper、ES Bulk Writer；Search Application 也直接依赖 ES alias/document 实现。结果是 Application 既编排业务，又掌握外部技术细节，模块无法独立测试和演进。

### 修复方案

在消费能力的模块内为“仍然存在的跨模块 Infrastructure 依赖”定义窄 Port；101B–107、109–110 为正确性修复已经引入的最小 Port 直接视为输入，不得复制一套同义接口。候选能力包括：

```text
DocumentParserPort
AssetLifecyclePort
SegmentIndexPort
StorageCredentialProvider
EmbeddingPort
ParseArtifactStore
IndexTopologyPort
```

规则：

1. Port 使用业务输入输出，不暴露 Elasticsearch Client、MyBatis Record、Spring `MultipartFile` 或 Docling HTTP DTO。
2. 由 integration/infrastructure Adapter 实现 Port。
3. `SearchObjectStoragePort.uploadFile(MultipartFile)` 改为接口层读取上传内容，再传业务无关的二进制输入对象。
4. ANCHR-105/106 已确定的 Docling submit/get/ack 契约保持不变，本卡只消除调用方对具体 Client/DTO 的残余依赖。
5. ANCHR-107 已确定的 generation 写入契约保持不变，本卡只替换 Application → ES writer 的直接依赖。

### 边界

本卡的完成标准是依赖方向改变、业务行为不变。不得借 Port 迁移重新设计 Docling 指纹、Ingestion 状态机、index generation 或 Retrieval Plan；这些变更必须回到各自主责卡。

### 验收

- Ingestion Application 不导入自身或其他模块的 infrastructure 类。
- Search Application 不导入 ES document/alias manager。
- Port 单测可使用内存 fake，失败、超时和部分成功可确定性复现。
- Adapter 替换不改变现有 REST、数据库和 ES 协议。

---

## ANCHR-204：选择性富领域化与聚合边界收口

**目标：** 将真正复杂的不变量从 SQL 更新和 Application 条件分支收口到领域行为，同时避免全项目形式化 DDD。

### 当前根因

`IngestionTask`/`IngestionTaskItem` 只有字段；状态迁移散落在 Repository SQL。`ConversationSession` 使用 `@Data` 暴露所有 setter，只有 `touch()` 少量行为。索引 generation、文档覆盖、会话标题 CAS 等规则如果继续只存在于 Service/Mapper 中，会再次被其他入口绕过。

### 聚合边界

明确为：

- `KnowledgeBase`：独立聚合，不加载全部 Asset；
- `Asset`：独立聚合，持有 kbId 和 active index generation；
- `IngestionTask`：独立聚合，TaskItem 在任务一致性边界内；
- `ConversationSession`：独立聚合；
- `ConversationTurn`：独立增长记录，以 sessionId 关联；
- `AgentTask/AgentRun`：独立聚合，不塞入 ConversationSession；
- `Segment`：搜索索引模型，不作为 Asset 内部实体集合加载。

### 修复方案

优先富领域化三组已确认规则：

1. Ingestion 状态机：
   - `claim/submitParse/observeParseResult/startEmbedding/startIndexing/complete/fail/scheduleRetry`；
   - 方法内部校验 execution epoch、stage attempt、lease、前置状态和时间单调性；
   - Repository 只做条件持久化，不自行决定状态跳转。
2. Asset/index generation：
   - `reserveGeneration/activateGeneration/markIndexFailed/softDelete`；
   - 删除后不得激活新 generation。
3. ConversationSession：
   - `rename/touchIfNewer/applyAutoTitleIfUnchanged/delete`；
   - 移除通用 setter，持久化重建使用受控工厂。

settings、Dashboard、Token、普通查询继续保持事务脚本/CRUD，不强行富模型化。

### 边界

106、106B、107、109 先拥有并验证状态、持久化边界、generation、CAS 的真实业务语义和数据库条件更新；本卡只在不改变字段、状态枚举、错误码和并发结果的前提下，把这些既有规则移动到领域行为。若领域化暴露语义缺陷，退回对应正确性卡修复，不能在本卡顺手改变业务结果。

### 验收

- 合法和非法状态迁移均有纯单元测试。
- `SUCCESS → RUNNING`、旧 execution epoch 写入、删除后激活、自动标题覆盖手工标题均被领域规则拒绝。
- 领域模型不依赖 Spring、MyBatis、REST DTO。
- Repository 条件更新结果与领域版本/lease 一致。

---

## ANCHR-205：Outbox 从 KB Domain 拆为可靠消息模块

**目标：** 保留现有可靠投递能力，同时把技术租约模型与 KB 领域语言分开。

### 当前根因

`OutboxEvent` 当前位于 KB Domain，却包含 `lockToken/lockedAt/nextRetryAt/retryCount/processedAt/lastError`。这些是可靠消息基础设施状态，不是 KnowledgeBase 或 Asset 的领域属性，也会阻碍 Ingestion/index generation 等其他模块复用 outbox。

### 修复方案

1. 新建 `integrationevent` 或 `common/outbox` 模块边界：
   - application：发布、claim、完成、重试用例；
   - domain：通用投递状态和重试策略；
   - infrastructure：表、MyBatis Mapper、Scheduled Processor。
2. KB/Asset 只产生业务事件：
   - `AssetDeletedEvent`
   - `AssetGenerationRetiredEvent`
3. Application 在同一事务将业务事件转换成 Outbox Record。
4. 表结构、claim token、`SKIP LOCKED`、backoff、保留期保持不变，先做边界移动而不是重写机制。
5. ANCHR-107 的旧 generation 清理复用同一个发布接口。

### 边界

本卡是模块归属迁移，不是 Outbox v2。表字段、事件触发条件、claim/lease、backoff、失败分类、保留期和调度频率均保持不变；107 继续拥有 generation 事件何时产生，本卡只提供不依赖 KB package 的发布与消费实现。

### 验收

- KB Domain 不再出现 lock token、retry count 等投递字段。
- 删除和 generation 清理仍与业务事务原子写入 outbox。
- 现有失败重试、租约抢占和清理测试全部通过。
- 其他模块可以发布事件而不依赖 KB package。

---

## ANCHR-206：按用例拆分超大 Application Service

**目标：** 在边界和领域规则稳定后，降低超大类的认知复杂度，不改变业务流程。

### 当前事实

- `SegmentIndexManagerImpl` 约 1168 行；
- `AgentWorkflowImpl` 约 921 行；
- `ConversationServiceImpl` 约 894 行；
- `UnifiedSearchServiceImpl` 约 885 行；
- `IngestionApplicationServiceImpl` 与 `IngestionTaskProcessorImpl` 合计约 888 行。

问题不只是行数，而是一个类同时承担编排、算法、协议、持久化协调、映射和指标。

### 修复方案

在 ANCHR-202–205 完成后按稳定职责拆分：

- Search：`HybridRecallUseCase`、`RrfFusionPolicy`、`RerankPolicy`、`SearchResultAggregator`、`SearchInsightFactory`；
- Conversation：`SessionCommandService`、`ConversationHistoryQueryService`、`SendMessageUseCase`、`ConversationPersistenceCoordinator`、`ConversationTitlePolicy`；
- Ingestion：`IngestionScheduler`、`ParseStageHandler`、`EmbeddingStageHandler`、`IndexStageHandler`、`IngestionFailurePolicy`；
- Index management：alias topology、migration、validation、write barrier 分为独立用例/策略。

RRF、分数融合、cursor codec、状态迁移等纯逻辑类不得依赖 Spring，使用普通单元测试。主 Application Service 只保留用例编排。

不把拆分类与业务逻辑修改放在同一个 commit；先用 characterization tests 锁定行为，再机械迁移，最后删除旧 facade 内实现。

### 边界

本卡只能机械拆分 202–205 已经稳定的职责。不得新增 Port/领域状态/数据库字段，不得修改 ES mapping、模型调用顺序、RRF/Rerank 参数、cursor、SSE/REST 协议或 Outbox 策略。拆分过程中发现行为问题必须另开或退回主责卡，不能以“顺手清理”为由混入。

### 验收

- REST/SSE/任务状态和模型调用顺序与迁移前一致。
- 主用例类只依赖 Command/Result、Domain Policy 和 Port。
- 纯算法和状态规则无需 Spring Context 即可测试。
- 删除旧兼容构造器、optional field injection 和跨层 null 兼容分支。

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

- ANCHR-106B：拆分任务项、current execution、artifact registry 和公开查询投影。

必须先用 106 的回归测试锁定状态机行为，再迁移物理边界；旧、新 worker 不得混跑。106B 的 normalized schema/API 源码稳定后可以开始 107 的源码开发和隔离测试，但 107 不得进入生产发布或接管真实 `INDEX` 流量，直到 106B 在业务 MySQL 上处理失败 history、完成 V18 停机迁移，并通过 lease/retry/artifact、公开投影和部署稳定观察。该 Wave 不增加 Asset generation，也不改变前端任务协议。

### Wave 5：Asset Segment 写入一致性

- ANCHR-107：Asset indexGeneration、目标 generation 清理重写、可见性和可重放变化。

该 Wave 只接管 `INDEX` stage并提供一致性对账能力，不创建 physical index version。107 的生产发布必须显式记录所依赖的 106B 数据库验收证据，不能用 107 自身测试替代该门禁。

### Wave 6：单向量 Profile 投影契约

- ANCHR-101B：单 `embedding`、统一 Projection Policy、唯一向量字段和输入来源指标。

使用隔离索引验证：文本请求和图片请求使用同一个多模态模型配置，查询和写入都只访问 `embedding` 字段；保留 107 的 generation/ID/可见性规则。本 Wave 不新增向量字段、不切换生产 alias。

### Wave 7：Embedding Profile 部署

- ANCHR-101C：内存目标 profile、全程 JVM 写屏障、physical index rebuild、alias 切换后启用配置。

只有 101B Projection Policy/唯一向量字段契约和 107 change log 都稳定后才能执行生产重建。

### Wave 8：文档内嵌图片检索

- ANCHR-110：图片 artifact/bbox、`DOCUMENT_IMAGE`、图片对象随 Asset generation 清理、同字段召回分路、父文档聚合和 Preview。

先完成 Docling/app 契约和存量 reparse 影子验证，再开放后端召回与前端 hit type。110 不新增第二向量字段，不绕开 101C 直接切换物理索引。

### Wave 9–14：DDD 与架构治理，严格串行

```text
201 架构适应度规则
 → 202 REST DTO/SSE 边界
 → 203 模块 Port/Adapter
 → 205 Outbox 模块迁移
 → 204 既有规则领域化
 → 206 Application Service 机械拆分
```

205 先于 204，使领域化后的 Asset/Ingestion 只产生业务事件，不重新引入 KB Outbox 技术模型。206 必须最后执行。不在 101A–101C、102–107、109–110 的正确性 PR 中顺手搬包或拆类。

### 总关键路径

```text
Wave 0
├─ 101A → 104 → 105 ┐
│                    ├→ 106 → 106B → 107 → 101B → 101C → 110 ┐
├─ 102  → 103 ──────┘                                        ├→ 201 → 202 → 203 → 205 → 204 → 206
└─ 109 ──────────────────────────────────────────────────────┘
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
| DOCUMENT_IMAGE 模型切换（110） | 按 segmentType 投影、存量 reparse、alias/回滚 | 能力降级说明 | Parse artifact object key 可重放、OCR/caption fixture |
| 内嵌图片删除/重解析（110） | PARSE_RESULT 图片清单、既有清理事件、对账 | 无陈旧缩略图 | 同 attempt object key 幂等 |
| 500 个会话 | keyset、同时间戳 | 自动/手动加载 | 不涉及 |
| 重命名与消息并发 | CAS、时间单调 | 标题不回退 | 不涉及 |

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
12. ANCHR-107 必须验证 generation 激活门禁、部分 bulk 后目标 generation 清理重写、变化重放和清理失败，不承担 101C 的 physical index 验收。
13. ANCHR-110 必须验证图片 bbox 来自 Picture Item provenance、私有对象签名访问、存量 reparse、对象生命周期、同字段分路召回、父文档聚合和 Preview；不得以 `chunks[].bboxes` 猜测图片位置或增加第二 dense 字段。
