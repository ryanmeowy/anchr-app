# QAnything 方向能力补齐方案

## 背景与定位

当前项目已经具备 RAG 知识库检索引擎的核心链路：文件入库、OCR、Embedding、Elasticsearch 混合检索、RRF 融合、rerank、多轮对话、证据引用与预览定位。

如果后续向 QAnything 类产品靠拢，重点不是复制 UI 或功能列表，而是补齐一个可独立使用的本地知识库问答闭环：

- 用户可以创建知识库。
- 用户可以批量上传不同格式的文件。
- 系统可以稳定解析、切分、向量化、索引。
- 用户可以针对一个或多个知识库进行问答。
- 回答必须可追溯到原文、页码、表格行、图片 bbox 等证据位置。
- 系统可以本地部署，并支持云端/本地模型能力可插拔。

本方案聚焦五部分：

1. 文件知识库闭环
2. 文档格式能力
3. 解析质量与结构化 Chunk
4. 问答体验
5. 本地部署与模型可插拔

---

## 1. 文件知识库闭环

### 目标

将当前“入库 + 检索 + 对话”的能力升级为完整的知识库产品模型。用户不应直接感知底层 `kb_segment` 索引，而应围绕“知识库、文档、任务、片段”完成操作。

### 核心领域对象

#### KnowledgeBase

知识库是用户组织资料和限制检索范围的顶层对象。

建议字段：

| 字段 | 说明 |
|------|------|
| `kbId` | 知识库 ID |
| `name` | 名称 |
| `description` | 描述 |
| `status` | `ACTIVE` / `ARCHIVED` / `DELETING` |
| `documentCount` | 文档数量，可异步统计 |
| `segmentCount` | segment 数量，可异步统计 |
| `createdAt` / `updatedAt` | 创建和更新时间 |

#### DocumentAsset

文档资产是用户上传或导入的一份原始资料。

建议字段：

| 字段 | 说明 |
|------|------|
| `assetId` | 文档 ID |
| `kbId` | 所属知识库 |
| `fileName` | 原始文件名 |
| `fileType` | `PDF` / `DOCX` / `XLSX` / `IMAGE` / `URL` 等 |
| `mimeType` | MIME 类型 |
| `objectKey` | 对象存储 key |
| `sourceUrl` | URL 导入来源 |
| `fileHash` | 文件 hash，用于去重 |
| `sizeBytes` | 文件大小 |
| `parseStatus` | 解析状态 |
| `indexStatus` | 索引状态 |
| `segmentCount` | 生成 segment 数 |
| `embeddingProfile` | 入库时使用的 embedding profile |
| `errorMessage` | 失败原因 |
| `createdAt` / `updatedAt` | 创建和更新时间 |

#### IngestionTask

入库任务用于承载批量上传、解析、向量化、索引过程。

建议字段：

| 字段 | 说明 |
|------|------|
| `taskId` | 任务 ID |
| `kbId` | 目标知识库 |
| `status` | `PENDING` / `RUNNING` / `SUCCESS` / `PARTIAL_SUCCESS` / `FAILED` |
| `totalCount` | 总文件数 |
| `successCount` | 成功数 |
| `failureCount` | 失败数 |
| `runningCount` | 运行中数量 |
| `items` | 每个文件的任务项 |
| `createdAt` / `updatedAt` | 创建和更新时间 |

#### Segment

Segment 是检索和回答的最小证据单元。

建议字段：

| 字段 | 说明 |
|------|------|
| `segmentId` | segment ID |
| `kbId` | 所属知识库 |
| `assetId` | 所属文档 |
| `segmentType` | `TEXT_CHUNK` / `IMAGE_OCR_BLOCK` / `TABLE_ROW_GROUP` / `PPT_SLIDE` 等 |
| `contentText` | 供检索和 LLM 使用的正文 |
| `ocrText` | OCR 文本 |
| `tableText` | 表格文本 |
| `headingPath` | 标题路径 |
| `pageNumber` | PDF/PPT 页码 |
| `sheetName` | Excel sheet |
| `rowRange` / `columnRange` | 表格定位 |
| `bbox` | 图片/PDF 区域定位 |
| `sourceOrder` | 原文顺序 |
| `prevSegmentId` / `nextSegmentId` | 相邻片段 |
| `embeddingProfile` | 向量模型 profile |

### 必补能力

| 能力 | 说明 |
|------|------|
| 知识库管理 | 创建、删除、重命名、查看统计 |
| 文档上传 | 支持批量上传并异步入库 |
| 文档列表 | 展示文件名、格式、大小、解析状态、segment 数、失败原因 |
| 文档删除 | 删除对象存储文件、ES segment、任务记录 |
| 重新解析 | 解析策略变更后重建 segment |
| 重新向量化 | embedding 模型变更后重建向量 |
| 查询范围 | 搜索和问答时指定一个或多个知识库 |
| 去重策略 | 基于 `fileHash + kbId` 避免重复上传 |

### API 建议

```text
POST   /api/v1/kbs
GET    /api/v1/kbs
GET    /api/v1/kbs/{kbId}
PATCH  /api/v1/kbs/{kbId}
DELETE /api/v1/kbs/{kbId}

POST   /api/v1/kbs/{kbId}/documents
GET    /api/v1/kbs/{kbId}/documents
GET    /api/v1/kbs/{kbId}/documents/{assetId}
DELETE /api/v1/kbs/{kbId}/documents/{assetId}
POST   /api/v1/kbs/{kbId}/documents/{assetId}/reparse
POST   /api/v1/kbs/{kbId}/documents/{assetId}/reembed

POST   /api/v1/kbs/{kbId}/ingestion-tasks
GET    /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}
POST   /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed

POST   /api/v1/kbs/{kbId}/search
POST   /api/v1/kbs/{kbId}/conversations
```

### 验收标准

- 用户能创建一个知识库并上传一批文件。
- 用户能查看每个文件的入库状态和失败原因。
- 入库成功后，可以只在指定知识库内搜索和问答。
- 删除文档后，搜索和问答不再命中该文档的 segment。
- 解析策略或 embedding 模型变化后，有明确的 reparse / reembed 操作入口。

---

## 2. 文档格式能力

### 目标

QAnything 方向的核心是“用户把资料丢进来就能问”。文档格式能力决定产品是否可用。当前已有 PDF/TXT/Markdown/图片能力，下一阶段应补企业知识库最常见格式。

### 优先级

| 优先级 | 格式 | 原因 |
|--------|------|------|
| P0 | DOCX | 企业文档、方案、说明书最常见 |
| P0 | XLSX / CSV | 表格知识库需求强，普通 RAG 容易做差 |
| P1 | PPTX | 培训材料、技术方案、销售材料常见 |
| P1 | HTML / URL | 支持网页知识库和在线文档导入 |
| P2 | ZIP | 批量导入体验，不是解析格式本身 |
| P2 | JSON / XML / EPUB | 后续扩展，不作为第一批重点 |

### 格式解析要求

| 格式 | 不应只做 | 应保留的信息 |
|------|----------|--------------|
| PDF | 不只抽纯文本 | 页码、段落、标题、表格、图片/OCR 坐标 |
| DOCX | 不只拼接全文 | heading 层级、段落、表格、列表、页内图片 |
| XLSX | 不只整表转文本 | sheet、表头、行列、单元格坐标、合并单元格 |
| CSV | 不只拼接行 | 表头、行号、列名、分隔符识别 |
| PPTX | 不只抽文本框 | slide 页码、标题、正文、备注、图片 OCR |
| HTML | 不只抓 body text | title、h1-h6、正文块、代码块、链接上下文 |
| 图片 | 不只生成摘要 | OCR block、bbox、原图尺寸、纠错后文本 |

### 解析库建议

| 格式 | Java 侧可选方案 |
|------|----------------|
| DOCX | Apache POI XWPF |
| XLSX / CSV | Apache POI XSSF / Commons CSV |
| PPTX | Apache POI XSLF |
| HTML / URL | Jsoup |
| PDF | 继续使用 PDFBox，后续补版面分析 |
| 图片 OCR | 保留传统 OCR + LLM OCR 双路 |

### URL 导入能力

URL 导入建议拆成三层：

1. 单 URL 导入：抓取页面标题、正文、代码块、链接文本。
2. Sitemap 导入：批量抓取站点文档。
3. 增量同步：基于 URL、ETag、Last-Modified 或内容 hash 判断是否重建。

### ZIP 批量导入能力

ZIP 不是一种内容格式，而是批处理入口。

要求：

- 解压后识别内部文件类型。
- 跳过系统文件，如 `.DS_Store`。
- 对每个文件生成独立任务项。
- 单个文件失败不影响其他文件。
- 保留压缩包内路径作为文档 metadata。

### 验收标准

- DOCX、XLSX、PPTX、HTML/URL 上传后均能进入统一入库任务。
- 搜索命中结果能展示格式相关来源信息，例如 DOCX 标题路径、XLSX sheet/行列、PPT 页码、URL 原地址。
- 失败文件能展示明确原因，而不是仅返回通用异常。
- 表格内容可以被问答命中，并能定位到 sheet 和行列范围。

---

## 3. 解析质量与结构化 Chunk

### 目标

普通 RAG 的 chunk 往往是固定长度切文本。当前项目已经有 OCR bbox 和 segment 设计基础，后续应升级为结构感知 chunk：让每个 chunk 知道自己来自哪个标题、页码、表格行、图片区域，以及与前后 chunk 的关系。

### Segment 结构建议

```text
segmentId
kbId
assetId
segmentType
contentText
headingPath
pageNumber
sheetName
rowRange
columnRange
bbox
sourceOrder
prevSegmentId
nextSegmentId
metadata
embeddingProfile
```

### Chunk 策略

| 内容类型 | 策略 |
|----------|------|
| 普通段落 | 按标题层级 + 段落聚合，超长再滑窗 |
| PDF | 页内段落 chunk，保留 pageNumber |
| DOCX | heading path + 段落/列表 chunk |
| 表格 | 小表整表，大表按行组 chunk |
| 图片 OCR | paragraph/block 级 chunk，保留 bbox |
| PPT | slide 级基础 chunk，标题/正文分块 |
| 代码块 | 单独 chunk，不混入自然语言段落 |

### Heading Path 注入

很多文档片段本身语义不完整。例如正文只有：

```text
默认值为 30s。
```

如果保留标题路径：

```text
安装部署 > 高级配置 > 请求超时
```

检索和回答质量会明显提升。

建议在索引中同时存储：

- `headingPath`：结构化数组或字符串。
- `contentText`：原始正文。
- `expandedText`：`headingPath + contentText`，用于 embedding 或 BM25 加权。

### 表格结构化

表格建议同时生成三类表达：

| 字段 | 用途 |
|------|------|
| `tableMarkdown` | 给 LLM 阅读 |
| `tablePlainText` | 给 BM25 检索 |
| `cellMetadata` | 用于定位 sheet、行、列、单元格 |

大表格不建议整表一个 chunk。推荐：

- 表头 + N 行为一个 chunk。
- 每个 chunk 保留 `rowRange`。
- 合并单元格展开到相关行列。
- 空表头用上方或左侧上下文补全。

### 上下文扩展

检索命中某个 chunk 后，回答上下文不应只带该 chunk。应支持按策略扩展：

| 策略 | 说明 |
|------|------|
| 前后文扩展 | 带上 `prevSegmentId` / `nextSegmentId` |
| 同标题扩展 | 带上同一 heading 下的相邻 chunk |
| 表格扩展 | 命中某一行时带上表头和相邻行 |
| 图片扩展 | 命中 OCR block 时带上图片 caption |

扩展后的上下文要受 token budget 控制，不能无限拼接。

### 解析预览

文档详情页应提供解析预览，帮助用户和开发者理解系统如何切分文档。

建议展示：

- 原始文档信息。
- 解析后的 segment 列表。
- 每个 segment 的标题路径、页码、行列、bbox。
- 当前 embedding profile。
- 是否存在解析降级。

### 重建机制

| 触发条件 | 操作 |
|----------|------|
| chunk size / overlap 变化 | 对新入库生效；旧文档需要 reparse |
| 解析器升级 | 旧文档需要 reparse |
| embedding model 变化但 dimension 不变 | 旧文档需要 reembed |
| embedding dimension 变化 | 需要新索引 + 全量 reembed |
| OCR provider 变化 | 图片类文档需要 reparse |

### 验收标准

- 搜索结果返回结构化来源，而不是只有文本。
- 问答引用能定位到页码、标题路径、表格行列或图片 bbox。
- 文档详情页能展示解析后的 chunk 列表。
- chunk 策略变化后，有明确的 reparse / reembed 路径。
- 表格问答能稳定使用表头和行列信息，不只依赖纯文本相似度。

---

## 4. 问答体验

### 目标

从“能生成回答”升级为“可信、好用、可解释的知识库问答”。用户需要看到答案，也需要看到答案来自哪里；开发者需要看到检索链路，定位质量问题。

### 推荐问答链路

```text
用户问题
  -> 多轮 Query Rewrite
  -> 选择知识库范围
  -> BM25 + Vector 双路召回
  -> RRF 融合
  -> Rerank 精排
  -> Asset / Segment 聚合
  -> 上下文扩展
  -> LLM 生成
  -> 引用校验
  -> 返回 answer + citations + trace
```

### 必补体验

| 能力 | 说明 |
|------|------|
| SSE 流式输出 | 聊天体验必须支持逐步输出 |
| 引用点击 | `[1]` 点击打开原文定位 |
| 多知识库选择 | 会话绑定一个或多个知识库 |
| 检索 trace | 展示 rewrite、BM25、vector、RRF、rerank |
| 答案模式 | 严格问答、总结、对比、仅检索不生成 |
| 无答案策略 | 资料不足时明确说明，不编造 |
| 推荐追问 | 基于本轮证据生成后续问题 |
| 会话管理 | 新建、重命名、删除、历史分页 |

### Citation 结构建议

```text
citationNo
segmentId
assetId
fileName
quote
pageNumber
headingPath
sheetName
rowRange
columnRange
bbox
previewUrl
```

### Retrieval Trace 结构建议

Trace 面向开发者和高级用户，用于解释为什么这个答案质量好或差。

建议字段：

| 字段 | 说明 |
|------|------|
| `originalQuery` | 用户原始问题 |
| `rewrittenQuery` | 多轮改写后的问题 |
| `rewriteReason` | 改写原因 |
| `kbScope` | 检索的知识库范围 |
| `bm25Hits` | 文本召回结果 |
| `vectorHits` | 向量召回结果 |
| `rrfCandidates` | 融合候选 |
| `rerankResults` | 精排结果 |
| `selectedSegments` | 最终进入 prompt 的证据 |
| `answerFallback` | 是否发生回答降级 |
| `fallbackReason` | 降级原因 |

### 前端布局建议

正式问答页建议三栏：

```text
左侧：知识库 / 会话列表
中间：聊天
右侧：引用来源 / 检索 Trace / 原文预览
```

右侧区域可切换：

- 引用来源
- 检索过程
- 原文预览
- 调试 JSON

### 答案模式

| 模式 | 行为 |
|------|------|
| 严格问答 | 只能基于检索证据回答，默认模式 |
| 总结模式 | 对多个文档进行归纳总结 |
| 对比模式 | 比较多个文档或多个方案 |
| 仅检索 | 不调用 LLM，只返回 TopN 证据 |
| 调试模式 | 展示完整 trace 和 prompt 摘要 |

### SSE 输出建议

可以分阶段返回：

```text
event: trace
data: {"rewrittenQuery":"..."}

event: delta
data: {"text":"..."}

event: citations
data: [...]

event: done
data: {"turnId":"..."}
```

### 验收标准

- 用户发起问题后能看到流式回答。
- 回答中的引用可以点击并定位到源文档。
- 同一个会话可以绑定知识库范围。
- 回答无证据时不会编造。
- 开发者能通过 trace 判断问题出在召回、排序还是生成。

---

## 5. 本地部署与模型可插拔

### 目标

向 QAnything 方向靠拢必须降低部署门槛，并支持云端/本地模型能力可替换。当前项目已经有能力端口和 provider 配置基础，后续应补 OpenAI-compatible、本地模型、本地 OCR、docker compose 一键启动和配置页。

### 能力分类

| 能力 | 云端候选 | 本地候选 |
|------|----------|----------|
| Embedding | DashScope / 火山 | bge-m3、bge-large-zh、本地 gRPC |
| Generation | DashScope / OpenAI-compatible | Ollama、vLLM、LM Studio |
| Rerank | gte-rerank / 云 rerank | bge-reranker |
| OCR | 阿里云 OCR | PaddleOCR / RapidOCR |
| Object Storage | 阿里云 OSS / 火山 TOS | MinIO / 本地文件存储 |

### OpenAI-compatible 优先

第一阶段建议优先支持 OpenAI-compatible 协议。这样可同时兼容：

- OpenAI
- DashScope OpenAI-compatible
- DeepSeek
- 本地 vLLM
- LM Studio
- Ollama OpenAI-compatible endpoint

建议配置：

```text
OPENAI_BASE_URL
OPENAI_API_KEY
GEN_MODEL
EMBEDDING_MODEL
```

### 部署模式

| 模式 | 组件 |
|------|------|
| `cloud-minimal` | backend + frontend + Elasticsearch + Redis，模型走云 |
| `local-lite` | backend + frontend + Elasticsearch + Redis + Ollama |
| `local-full` | backend + frontend + Elasticsearch + Redis + embedding service + rerank service + OCR service |
| `dev` | Elasticsearch + Redis + backend + Streamlit/Next.js |

### Docker Compose 目标形态

```text
backend
frontend
elasticsearch
redis
optional: ollama
optional: local-embedding-service
optional: local-rerank-service
optional: local-ocr-service
optional: minio
```

### 配置页能力

配置页建议支持三类配置：

#### 云厂商配置

- 阿里云 OSS endpoint、bucket、roleArn。
- 阿里云 OCR endpoint。
- DashScope API Key。
- 火山引擎 AK/SK、region、endpoint。

#### 模型配置

- Embedding backend、model、dimension、image input mode。
- Generation provider、model、base URL。
- Rerank provider、model。
- Local gRPC service address 和 deadline。

#### 能力配置

- OCR provider。
- Gen provider。
- Rerank provider。
- Object storage provider。
- RRF/rerank 开关。
- RRF rank constant、candidate multiplier。
- rerank window size、fusion alpha/beta。
- chunk size、chunk overlap。

### 热更新边界

第一版不要承诺所有配置热切换。当前大量 provider 是启动时装配，热切换需要引入运行时 Router。

| 配置类型 | 第一版策略 |
|----------|------------|
| 检索阈值、RRF 参数、rerank window | 可较早支持热更新 |
| chunk size / overlap | 新入库生效；旧文档需 reparse |
| generation model | 可先重启生效，后续再热更新 |
| embedding model | 需要 reembed |
| embedding dimension | 需要新索引 + 全量重建 |
| OCR provider | 第一版重启生效 |
| object storage provider | 第一版重启生效 |
| Redis / ES 连接 | 不建议页面热切 |

### Provider Router 演进

如果后续要运行时切换 provider，应从 `@ConditionalOnProperty` 启动期装配演进为运行时路由。

建议新增：

```text
GenerationRouter
EmbeddingRouter
OcrRouter
RerankRouter
ObjectStorageRouter
```

Router 根据运行时配置选择具体实现。该改造影响面较大，不建议放在第一阶段。

### 验收标准

- 新用户可以通过 `.env` 或配置页填入 key 后完成部署。
- `docker compose up` 可以启动基础依赖和应用。
- 没有云 key 时，也能通过本地模型完成基本问答。
- 云端和本地模型切换不需要改业务代码。
- embedding dimension 变化时，系统能明确提示需要重建索引。

---

## 推荐实施顺序

### Phase 1：知识库产品模型

- 新增 KnowledgeBase / DocumentAsset / IngestionTask 产品模型。
- 搜索和问答支持 `kbId` 范围。
- 文档列表支持状态、失败原因、删除、重试。

### Phase 2：正式问答体验

- 支持多知识库问答。
- 引用点击定位。
- Retrieval Trace 产品化。
- SSE 流式回答。

### Phase 3：文档格式扩展

- P0：DOCX、XLSX、CSV。
- P1：PPTX、HTML/URL。
- P2：ZIP 批量导入。

### Phase 4：结构化 Chunk

- heading path。
- 表格 chunk。
- 上下文扩展。
- 解析预览。
- reparse / reembed 闭环。

### Phase 5：本地部署与模型可插拔

- OpenAI-compatible generation。
- Ollama / vLLM / LM Studio 接入。
- 本地 embedding / rerank / OCR 服务。
- docker compose 多模式部署。
- 配置页与连接测试。

---

## 优先级总表

| 模块 | 优先级 | 说明 |
|------|--------|------|
| 文件知识库闭环 | P0 | 产品地基，必须先做 |
| 问答体验 | P0 | 用户最直接感知 |
| DOCX / XLSX / CSV | P0 | 企业知识库高频格式 |
| 结构化 Chunk | P1 | 项目差异化核心 |
| OpenAI-compatible / Ollama | P1 | 降低模型接入和本地部署门槛 |
| PPTX / URL / ZIP | P1 | 扩大可用范围 |
| Provider Router 热切换 | P2 | 架构改造较大，后置 |
| 配置版本回滚和审计 | P2 | 产品成熟期能力 |

---

## 总结

向 QAnything 方向演进时，最重要的是补齐“知识库产品闭环”，而不是继续只堆检索算法。当前项目已有混合检索、OCR bbox、证据引用等优势，后续应围绕这些优势形成差异化：

- 文件知识库闭环是地基。
- 文档格式能力决定用户能否真实使用。
- 结构化 chunk 决定检索和回答质量上限。
- 问答体验决定用户是否信任结果。
- 本地部署与模型可插拔决定是否符合 QAnything 类产品的核心预期。

