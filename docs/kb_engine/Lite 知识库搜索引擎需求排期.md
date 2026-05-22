# Lite 知识库搜索引擎需求排期

## 1. 排期说明

| 项目 | 内容 |
|---|---|
| 项目名称 | Text + Image Lite Knowledge Base Search Engine |
| 开发模式 | 个人开发 + Codex 辅助 |
| 日均投入 | 3 小时 / 天 |
| 总排期 | 16 周 |
| 排期策略 | 先统一检索底盘，再做简历版，再做对话式检索，最后做关键增强 |
| 当前已具备能力 | 图片解析、入库状态、应用层 RRF、Cross-Encoder rerank、召回解释、入库重试、系统指标 |
| 本次新增重点 | 文本入库、统一 segment 检索、text + image 联合检索、对话式检索 |

---

## 2. 总体阶段排期

| 阶段 | 周期 | 目标 | 核心交付 |
|---|---:|---|---|
| Phase 1 | 第 1-8 周 | 把现有图片检索底盘扩成 text + image 统一检索底盘 | 文本入库、chunk、统一 segment、联合检索 |
| Phase 2 | 第 9-12 周 | 把项目打磨成简历版 | 统一 explain / rerank、结果聚合、离线评测、README |
| 专项：对话式检索 | 第 13-16 周 | 支持多轮对话检索和带引用回答 | 会话、query rewrite、answer + citations |
| Phase 3（关键增强） | 16 周后可选 | 增强工程性和调优能力 | 版本化、局部重建、参数实验、实验报告 |

---

## 3. 里程碑定义

| 里程碑 | 目标时间 | 判定标准 |
|---|---:|---|
| M1 | 第 4 周结束 | 文本文件可上传、入库、切 chunk、写入索引 |
| M2 | 第 8 周结束 | 一个 query 可同时召回文本和图片，返回统一结果 |
| M3 | 第 12 周结束 | 项目具备评测、README、可面试讲述能力 |
| M4 | 第 16 周结束 | 支持多轮对话检索，回答带引用，可展示对话式检索能力 |

---

## 4. 周级需求排期

| 周次 | 阶段 | 周目标 | 核心需求 / 开发任务 | 交付物 | 备注 |
|---:|---|---|---|---|---|
| 第 1 周 | Phase 1 | 完成文本检索底盘设计 | 明确 Asset / Segment / Anchor / IngestJob 模型；设计文本入库链路；确定统一 segment 检索方案 | 领域模型草案、表结构草案、索引结构草案 | 以最小改动复用现有图片检索架构 |
| 第 2 周 | Phase 1 | 打通文本上传与任务接入 | 支持 PDF / TXT / MD 上传；接入现有异步任务体系；文本资产入库状态可查询 | 文本上传接口、任务接入能力 | 复用现有入库状态和重试机制 |
| 第 3 周 | Phase 1 | 实现文本解析与 chunk | PDF 页级解析；TXT/MD 分块；生成 chunk 元数据；保留 pageNo / chunkOrder | 文本 chunk processor、文本 segment 生成能力 | 先做稳定版本，不追求复杂排版恢复 |
| 第 4 周 | Phase 1 | 文本 segment 入索引 | 文本 embedding 接入；文本 segment 落库 / 入 ES；完成文本检索最小闭环 | 文本 segment 索引、最小文本搜索接口 | **M1 达成** |
| 第 5 周 | Phase 1 | 统一检索对象 | 定义统一 KbSegmentDocument；兼容 TEXT_CHUNK / IMAGE_CAPTION / IMAGE_OCR_BLOCK；统一结果协议 | 统一 segment document、统一 search result DTO | 这一周是 text + image 融合的关键 |
| 第 6 周 | Phase 1 | 打通 text + image 联合召回 | 在现有 search 子域中接入文本召回；支持文本 chunk、图片 OCR、图片 caption 共同参与召回 | 联合召回能力、统一搜索接口 V1 | 复用现有应用层 RRF |
| 第 7 周 | Phase 1 | 统一结果展示 | Streamlit 搜索页调整；展示文本片段、页码、图片缩略图、命中摘要 | 搜索页 V1 | 前端只做展示，不承载业务逻辑 |
| 第 8 周 | Phase 1 | 稳定 text + image 搜索闭环 | 联调、修 bug、补最小 explain 适配、跑通完整演示 | 可演示的 text + image 搜索系统 | **M2 达成** |
| 第 9 周 | Phase 2 | 统一领域叙事 | 明确项目对外定位为 lite 知识库搜索引擎；梳理 explain / result / candidate 的统一模型 | 统一模型文档、接口规范 V2 | 从“图片搜索”转为“知识检索”叙事 |
| 第 10 周 | Phase 2 | 统一 explain 与 rerank | explain 扩展到 text + image；rerank 输入统一为 segment candidate；topN / topK 配置化 | explain V2、rerank V2 | 复用现有 rerank 基础设施 |
| 第 11 周 | Phase 2 | 结果聚合与评测集 | 同一 asset 多命中结果聚合；构建 30-50 条最小评测集；准备 Recall@K / MRR / NDCG 脚本 | 聚合展示、评测脚本、评测数据 | 这是简历可信度关键周 |
| 第 12 周 | Phase 2 | 完成简历版打磨 | 输出 README、架构图、检索链路图、项目说明；整理评测结果；完善演示页面 | README V2、架构图、简历项目描述初稿 | **M3 达成** |
| 第 13 周 | 对话专项 | 建立会话模型 | 设计 ConversationSession / ConversationTurn；新增会话接口；支持创建会话和保存消息 | 会话表结构、会话接口 V1 | 先做会话，不急着做复杂回答 |
| 第 14 周 | 对话专项 | 支持多轮 query rewrite | 读取最近 3-5 轮上下文；实现 query rewrite；把 rewrite 后的 query 接入现有搜索链路 | rewrite 服务、对话检索编排 V1 | 第二轮问题可继承第一轮上下文 |
| 第 15 周 | 对话专项 | 回答生成与引用 | 基于 grounding segments 生成回答；返回 citations；支持无证据时保守回答 | answer generation、citations、对话页 V1 | 回答必须受控于检索结果 |
| 第 16 周 | 对话专项 | 对话式检索专项收口 | 增加 suggested questions；补会话层指标；联调和演示优化 | 对话式检索 MVP、会话指标、演示流程 | **M4 达成** |

---

## 5. 阶段需求拆分表

### 5.1 Phase 1：统一检索底盘

| 模块 | 需求项 | 优先级 | 完成标准 |
|---|---|---:|---|
| 文本入库 | 支持 PDF / TXT / MD 上传 | P0 | 文件可进入现有任务体系 |
| 文本解析 | PDF 页级解析、TXT/MD 分块 | P0 | 文本可生成 chunk |
| 文本索引 | 文本 chunk embedding + 入索引 | P0 | 文本可被检索命中 |
| 统一模型 | TEXT_CHUNK / IMAGE_CAPTION / IMAGE_OCR_BLOCK 统一建模 | P0 | 一个 query 可统一召回 |
| 搜索接口 | text + image 统一搜索 | P0 | 返回统一结果协议 |
| 页面展示 | 搜索页展示文本+图片结果 | P1 | 可演示、可截图 |

### 5.2 Phase 2：简历版

| 模块 | 需求项 | 优先级 | 完成标准 |
|---|---|---:|---|
| 统一 explain | explain 覆盖 text + image | P0 | 能解释命中来源 |
| 统一 rerank | segment candidate rerank | P0 | 排序逻辑统一 |
| 结果聚合 | 同一 asset 多命中结果聚合 | P1 | 结果更像知识库，不是散列表 |
| 离线评测 | 最小 query set + 效果指标 | P0 | 输出 Recall@K / MRR / NDCG |
| 文档包装 | README / 架构图 / 项目讲稿 | P0 | 可直接写进简历 |

### 5.3 对话式检索专项

| 模块 | 需求项 | 优先级 | 完成标准 |
|---|---|---:|---|
| 会话管理 | session / turn 模型与接口 | P0 | 可创建会话并保存历史 |
| Query Rewrite | 多轮上下文补全与改写 | P0 | 第二轮问题可继承语境 |
| 检索编排 | rewrite 后调用现有检索链路 | P0 | 复用当前 hybrid search 底盘 |
| 回答生成 | grounding answer | P0 | 回答基于检索结果生成 |
| 引用返回 | citations | P0 | 每轮回答带来源 |
| 推荐追问 | suggested questions | P1 | 可连续探索主题 |

---

## 6. 依赖关系表

| 当前任务 | 依赖任务 | 说明 |
|---|---|---|
| 文本检索 | 文本上传、文本解析、chunk 生成 | 没有文本 segment 就没有文本搜索 |
| 统一 segment 检索 | 文本 segment + 现有图片 segment | text + image 联合检索的前提 |
| 统一 explain / rerank | 统一 segment candidate | 否则 explain 和 rerank 仍然是图片中心 |
| 离线评测 | text + image 联合检索已稳定 | 没有稳定系统就无法评测 |
| 对话式检索 | unified search 已完成 | 会话层不能先于底层检索完成 |
| 版本化 / 重建 / 参数实验 | 主流程稳定可运行 | 强化项不能抢在主流程前面做 |

---

## 7. 风险排期预案

| 风险 | 可能影响 | 应对策略 |
|---|---|---|
| PDF 解析效果不稳定 | 文本 chunk 质量差，影响检索 | 第一版先接受页级 + 粗粒度 chunk，后续优化 |
| text + image 统一模型改动大 | 影响现有搜索链路 | 采用增量兼容方式，不一次性替换旧接口 |
| 对话式 rewrite 质量不稳定 | 多轮检索偏题 | 先只使用最近 3-5 轮，限制 rewrite 范围 |
| 回答生成幻觉 | 降低可信度 | 强制 grounding + citations，无证据时保守回答 |
| Streamlit UI 复杂度上升 | 前端迭代慢 | 只做展示层，不引入复杂业务逻辑 |

---

## 8. 可选的 Phase 3 关键增强（16 周后）

| 需求项 | 优先级 | 说明 |
|---|---:|---|
| segment / ingest 版本化 | P1 | 支持 chunk 策略和 embedding 版本管理 |
| 局部重建 | P1 | 支持按 asset 或按类型重建 |
| 参数配置化 | P1 | chunk size / topN / topK / 权重可配 |
| 实验对比报告 | P1 | BM25 vs hybrid、rerank on/off 等对比 |
| 指标增强 | P1 | P50 / P95 / 重试率 / 命中来源占比 |

---

## 9. 最终交付目标

| 时间点 | 交付结果 |
|---:|---|
| 第 8 周 | 可演示的 text + image lite 知识库搜索引擎 |
| 第 12 周 | 可写入简历、可用于面试讲解的简历版项目 |
| 第 16 周 | 支持多轮会话、rewrite、grounding answer、citations 的对话式检索版本 |

---

## 10. 建议执行节奏

| 每周时间分配 | 占比 | 说明 |
|---|---:|---|
| 主功能开发 | 60% | 新需求实现 |
| 联调 / 修 bug | 15% | 保证每周可运行 |
| 测试 / 评测 | 15% | 防止最后补数据来不及 |
| 文档 / 截图 / README | 10% | 保证随时可展示 |