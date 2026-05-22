# PRD 2：Resume-ready 版本（增量简历版）

## 1. 目标定义

在 MVP 跑通后，把项目从“能用”提升到“能讲”。
这一阶段的重点不是继续横向加功能，而是把已有能力和新增文本能力组织成一个**完整的检索工程故事**。

目标是让你能这样讲：

“我基于已有图片检索底盘，扩展出 text + image 的统一知识库搜索，复用了异步任务、应用层 RRF、rerank、explain 和 metrics 框架，并补齐了 segment 建模与离线评测。”

## 2. 本阶段完成后，项目应具备什么特征

* 统一的 text + image 检索协议
* 统一的 result explain
* 可量化的检索效果
* 可说明的架构取舍
* 可写进简历的项目材料

## 3. 核心增量需求

### 3.1 统一领域模型

把系统正式从“图片文档模型”推进到“知识片段模型”。

要求：

* 明确 Asset / Segment / Anchor 概念
* 文本和图片结果使用统一返回模型
* Search 子域的 query / result / explain 以 segment 为中心

### 3.2 explain 扩展到 text + image

你现有 explain 已能区分 vector、filename、ocr、tag、graph 等命中来源。第二阶段要把 explain 正式扩展到统一知识检索语境。

新增要求：

* 文本结果标注：

    * semantic hit
    * keyword hit
    * page hit / chunk hit
* 图片结果保留：

    * vector hit
    * OCR hit
    * caption/tag hit

### 3.3 rerank 统一化

你现有搜索链路已经有手工 rerank 入口。第二阶段要把 rerank 从“图片检索能力”扩展成“统一 segment rerank 能力”。

要求：

* rerank 输入统一为 segment candidate
* 支持 text-text / text-image 的同一重排接口
* topN / topK 配置化

### 3.4 结果聚合展示

新增结果聚合规则：

* 同一 asset 下多个命中 chunk 可聚合展示
* 文本优先展示最相关 chunk
* 图片优先展示缩略图 + OCR 摘要

目的：

* 让结果更像知识库，而不是“散乱的命中片段列表”

### 3.5 最小离线评测集

第二阶段必须补评测，不然这个项目在简历里会偏虚。

要求：

* 至少 30–50 条 query
* query 覆盖文本、图片、混合命中三类
* 至少给出：

    * Recall@K
    * MRR 或 NDCG
* 至少做一次对比：

    * 纯文本召回 vs hybrid
    * 无 rerank vs 有 rerank

### 3.6 项目叙事材料

需要同步产出：

* 新版 README
* 新版系统架构图
* 检索链路图
* 一页评测结果图表
* 简历项目描述草稿

## 4. 本阶段不重复建设的能力

这些能力继续复用你当前已有实现，只做“统一化”和“扩展化”，不重做底层机制：

* 应用层 RRF
* explain 基础骨架
* rerank 调用链
* metrics 框架
* 异步任务与重试体系 

## 5. 验收标准

满足以下条件视为 Phase 2 完成：

1. text + image 使用统一 search result 协议
2. explain 可解释文本和图片命中来源
3. rerank 能处理统一 segment candidate
4. 有最小离线评测集和效果数据
5. README 能清楚说明设计、取舍和效果
6. 该项目可以直接提炼成简历项目描述

## 6. 产出物

* 统一 result / explain 模型
* segment rerank 接口
* 离线评测脚本和数据
* README / 架构图 / 简历文案
