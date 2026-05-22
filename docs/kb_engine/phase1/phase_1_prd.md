# PRD 1：Text + Image Lite Knowledge Base Search Engine（增量 MVP）

## 1. 目标定义

在保留你现有图片检索底盘的前提下，新增**文本资料入库与检索能力**，把系统从“图片中心”推进到“文本 + 图片统一检索”。

这一阶段的目标不是补 RRF、retry、metrics 这些通用能力，因为它们已经存在。第一阶段只解决一个核心问题：

**让文本内容成为和图片内容一样的一等检索对象。**

## 2. 本阶段完成后，用户能做什么

用户上传 PDF / Markdown / TXT 后，可以输入一句自然语言，例如：

* mysql 架构实现
* innodb buffer pool
* 主从复制原理

系统同时返回：

* 文本命中的 chunk 结果
* 图片命中的 OCR / caption 结果

并且文本结果要能定位到：

* 页码
* 片段摘要

## 3. 本阶段不做什么

这阶段明确不做：

* 视频
* 以图搜图扩展
* RAG / 问答
* 个性化排序
* 多租户
* 复杂前端改版

## 4. 核心增量需求

### 4.1 新增“文本资产”入库

在现有 ingestion 子域下，新增文本资产处理链路。

新增支持：

* PDF
* TXT
* Markdown

要求：

* 文件上传后进入现有任务体系
* 沿用现有异步任务状态机制
* 沿用现有失败重试机制
* 文本入库与图片入库共享统一任务视图

### 4.2 新增文本解析与 chunk 生成

系统需要把文本文件切成可检索片段。

要求：

* PDF 至少支持页级解析
* TXT / MD 支持按段落或固定长度切块
* 每个 chunk 需要保留：

    * assetId
    * segmentId
    * title / fileName
    * pageNo（如适用）
    * chunkText
    * chunkOrder

### 4.3 新增统一 segment 检索对象

当前项目搜索核心还是围绕图片文档组织。MVP 阶段需要新增统一的 segment 检索模型，最少先覆盖：

* TEXT_CHUNK
* IMAGE_CAPTION
* IMAGE_OCR_BLOCK

要求：

* 搜索结果以 segment 为主
* 图片结果仍能回到图片实体
* 文本结果能返回页码/片段

### 4.4 新增文本 embedding 接入

系统需要对文本 chunk 生成 embedding，以复用现有 hybrid search 路线。

要求：

* 文本 query 可生成 query embedding
* 文本 chunk 可生成 chunk embedding
* 在现有搜索子域中增加文本语义召回入口

### 4.5 新增文本检索接口

新增知识库搜索接口，支持文本 query 同时搜：

* 文本 chunk
* 图片 OCR
* 图片 caption

要求：

* 第一阶段可以先只支持单页结果
* 返回统一协议：

    * resultType
    * assetType
    * snippet
    * pageNo
    * score
    * explain（可复用现有 explain 风格）

## 5. 本阶段不重复建设的能力

以下能力默认复用现有实现，不纳入第一阶段新增范围：

* 批量异步入库
* 任务状态查询
* 单项/全量重试
* 应用层 RRF 框架
* 手工 rerank 框架
* explain 基础结构
* 指标埋点框架

## 6. 验收标准

满足以下条件视为 Phase 1 完成：

1. 支持上传 PDF / TXT / MD
2. 文本文件能被解析并切成 chunk
3. 文本 chunk 能写入统一检索索引
4. 一个 query 能同时召回文本结果和图片结果
5. 文本结果能展示页码和片段摘要
6. 文本入库能走现有任务状态和重试体系

## 7. 产出物

* 文本入库 API
* 文本 chunk processor
* 统一 segment document
* 首版 text + image 搜索接口
* 最小结果页或接口返回示例