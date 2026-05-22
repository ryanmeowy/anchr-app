# 技术方案

## 1 目标架构

Phase 1 的架构变化很明确：

* **ingestion 子域**新增文本资产链路
* **search 子域**从“图片文档检索”演进为“统一 segment 检索”
* **integration.ai** 继续提供 embedding 能力
* **前端 Streamlit** 只增加结果展示，不承载逻辑

### 新链路

| 链路     | 描述                                                                                              |
|--------|-------------------------------------------------------------------------------------------------|
| 文本上传链路 | 上传 PDF/TXT/MD → 创建资产 → 创建任务 → 解析文本 → 切 chunk → 生成 embedding → 写统一索引                             |
| 搜索链路   | 输入 query → query embedding → 统一召回文本 segment + 图片 segment → 复用现有 RRF / rerank / explain → 返回统一结果 |
| 结果展示链路 | Streamlit 调统一搜索 API → 渲染文本页码片段 + 图片摘要                                                           |

这正好对应 PRD 的核心范围：文本入库、chunk、统一 segment、文本 embedding、统一搜索接口。

---

## 3.2 领域模型

### 建议新增模型

| 模型                      | 作用         | 建议字段                                                  |
|-------------------------|------------|-------------------------------------------------------|
| `TextAsset` 或扩展 `Asset` | 表示文本文件资源   | `id,name,type,mimeType,storageUri,status`             |
| `TextChunk`             | 表示文本切块结果   | `segmentId,assetId,title,pageNo,chunkText,chunkOrder` |
| `SegmentType`           | 统一检索类型     | `TEXT_CHUNK, IMAGE_CAPTION, IMAGE_OCR_BLOCK`          |
| `KbSegmentDocument`     | 统一 ES 检索文档 | 见下节                                                   |
| `KbSearchResult`        | 统一搜索返回     | `resultType,assetType,snippet,pageNo,score,explain`   |

### 建模原则

不要新造一套“文本专用搜索返回”；
要让文本和图片都收敛到 **segment** 这个检索单位上。
这和 PRD 里“搜索结果以 segment 为主，图片结果仍能回到图片实体，文本结果能返回页码/片段”是对齐的。

---

## 3.3 统一索引设计

### `KbSegmentDocument` 建议字段

| 字段            | 类型             | 说明                                                 |
|---------------|----------------|----------------------------------------------------|
| `segmentId`   | keyword        | segment 唯一 ID                                      |
| `assetId`     | keyword        | 所属资产                                               |
| `assetType`   | keyword        | `TEXT` / `IMAGE`                                   |
| `segmentType` | keyword        | `TEXT_CHUNK` / `IMAGE_CAPTION` / `IMAGE_OCR_BLOCK` |
| `title`       | text + keyword | 文件名 / 标题                                           |
| `contentText` | text           | 文本 chunk 或 caption                                 |
| `ocrText`     | text           | 图片 OCR 文本，文本 chunk 可留空                             |
| `pageNo`      | integer        | 文本页码，图片可空                                          |
| `chunkOrder`  | integer        | 文本块顺序                                              |
| `embedding`   | dense_vector   | 统一向量字段                                             |
| `sourceRef`   | keyword        | 可回跳原图或原文件                                          |
| `createdAt`   | date           | 创建时间                                               |

### 设计取舍

这一阶段不建议一开始就拆成多索引~~。
先用一个统一 `kb_segment` 索引，把 text + image 跑通。
等 Phase 2/3 再考虑冷热分层和多索引。

---

## 3.4 ingestion 技术方案

### 入口

复用现有任务体系，只新增文本任务类型。

### 流程

| 步骤 | 说明                                                            |
|----|---------------------------------------------------------------|
| 1  | 上传文本文件，创建 Asset                                               |
| 2  | 创建 IngestJob，走现有异步任务体系                                        |
| 3  | 根据后缀选择 `PdfTextParser` / `PlainTextParser` / `MarkdownParser` |
| 4  | 解析结果送入 `ChunkSplitter`                                        |
| 5  | 生成 `TextChunk`                                                |
| 6  | 调 `EmbeddingPort` 生成向量                                        |
| 7  | 写入 `KbSegmentDocument`                                        |
| 8  | 更新任务状态                                                        |

### 关键点

* **不重做**任务状态、重试逻辑
* **新增**文本处理器和 chunk 流程
* **失败点**主要落在解析失败、embedding 失败、索引写入失败

---

## 3.5 search 技术方案

### 目标

在现有 search 子域中新增文本 segment 召回，而不是重写整套搜索。

### 查询链路

| 步骤 | 说明                                                  |
|----|-----------------------------------------------------|
| 1  | 接收 query                                            |
| 2  | 生成 query embedding                                  |
| 3  | 统一召回 `TEXT_CHUNK + IMAGE_CAPTION + IMAGE_OCR_BLOCK` |
| 4  | 复用现有应用层 RRF                                         |
| 5  | 复用现有 rerank                                         |
| 6  | 组装统一结果 DTO                                          |
| 7  | 返回 explain                                          |

### 关键点

* 现有图片召回逻辑尽量封装成 **segment source**
* 文本召回作为新增 source 挂进去
* 统一由现有 search orchestration 收口

---

## 3.6 包结构建议

基于现在的 layout，建议只做增量扩展。

### `ingestion`

| 位置                          | 新增内容                                   |
|-----------------------------|----------------------------------------|
| `ingestion/application`     | `TextAssetIngestionService`            |
| `ingestion/domain/model`    | `TextChunk`、`AssetType.TEXT`、必要的任务模型扩展 |
| `ingestion/infrastructure`  | 文本解析器、文本索引写入器                          |
| `ingestion/interfaces/rest` | 文本上传接口                                 |

### `search`

| 位置                               | 新增内容                           |
|----------------------------------|--------------------------------|
| `search/domain/model`            | `KbSearchResult`、`SegmentType` |
| `search/infrastructure/document` | `KbSegmentDocument`            |
| `search/application`             | `KbSearchService` 或扩展现有统一搜索服务  |
| `search/interfaces/rest/dto`     | 统一搜索返回 DTO                     |

### `integration.ai`

| 位置                       | 新增内容                                                    |
|--------------------------|---------------------------------------------------------|
| `integration/ai/port`    | 文本 embedding 统一接口，若已存在则补文本用法                            |
| `integration/ai/adapter` | `qwen3-vl-embedding` / `Doubao-embedding-vision` 文本调用适配 |

---

## 3.7 API 草案

### 文本上传

| 项目     | 设计                           |
|--------|------------------------------|
| Method | `POST`                       |
| Path   | `/api/ingestion/text-assets` |
| 输入     | 文件、可选 title/标签               |
| 输出     | `assetId`, `jobId`, `status` |

### 搜索

| 项目     | 设计                                                                       |
|--------|--------------------------------------------------------------------------|
| Method | `POST`                                                                   |
| Path   | `/api/search/kb`                                                         |
| 输入     | `query`, `topK`, 可选 filters                                              |
| 输出     | `results[]`，字段含 `resultType, assetType, snippet, pageNo, score, explain` |

这个接口设计直接对应 PRD 里要求的“首版 text + image 搜索接口”和统一返回协议。

---

## 3.8 DoD（完成定义）

### Phase 1 Done 应满足

| 项    | 标准                              |
|------|---------------------------------|
| 文本上传 | 支持 PDF/TXT/MD                   |
| 文本入库 | 能进现有任务体系，状态可查，可重试               |
| 文本解析 | 能切 chunk，并带 pageNo / chunkOrder |
| 统一索引 | 文本和图片都进入统一 segment 索引           |
| 统一搜索 | 一个 query 同时召回文本和图片              |
| 结果展示 | 文本结果展示页码和片段，图片展示摘要              |
| 演示   | 至少 3 个完整 demo 场景可跑通             |

---