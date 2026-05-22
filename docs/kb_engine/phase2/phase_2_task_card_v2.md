# Phase 2 任务卡（重构版 / 基于 PRD2）

> 依据：`phase_2_prd.md`
> 目标周期：第 9-12 周（4 周，日均 3 小时，约 60 小时）
> 目标：把 Phase 1 的“可用系统”升级为“可讲、可量化、可复盘的简历版项目”。

---

## 1. 边界与原则

### 1.1 本阶段必须完成（Must）

- 统一 text + image 结果协议（以 segment 为中心）
- explain 扩展到 text + image
- rerank 统一为 segment candidate 输入
- 30-50 条最小评测集 + 指标脚本（Recall@K / MRR 或 NDCG）
- README/架构图/项目叙事材料可直接用于简历讲述

### 1.2 本阶段不做（Out of Scope）

- 不引入多轮对话、rewrite、answer/citations
- 不新增视频、多租户、SaaS 权限体系
- 不重做 ingestion 主链路
- 不替换底层向量/检索引擎

### 1.3 实施原则

- 增量演进：保留现有可用链路，优先统一协议与编排
- 可验证优先：每个任务都有可运行验收标准
- 可回滚：涉及协议升级时保留兼容窗口

---

## 2. Epic 拆分

| Epic | 目标 | 对应 PRD | 优先级 |
|---|---|---|---:|
| E1 | 统一查询/结果协议与领域表达 | 3.1 | P0 |
| E2 | explain 扩展到 text + image | 3.2 | P0 |
| E3 | rerank 统一为 segment candidate | 3.3 | P0 |
| E4 | 结果聚合展示（asset 维度） | 3.4 | P1 |
| E5 | 离线评测集与指标脚本 | 3.5 | P0 |
| E6 | README/架构图/简历文案 | 3.6 | P0 |

---

## 3. 任务卡明细

## E1：统一协议与领域表达

| 卡片ID | 标题 | 依赖 | 输出 | 验收标准 |
|---|---|---|---|---|
| P2-01 | 定义统一查询 DTO | 无 | `UnifiedSearchQueryDTO`（或等价协议） | 覆盖 query、topK、limit、strategy、rerank 开关 |
| P2-02 | 定义统一结果 DTO | P2-01 | `UnifiedSearchResultDTO`（item 级 segment 结构） | 覆盖 `segmentId/assetId/assetType/segmentType/content/score/explain/sourceRef/anchor/thumbnail/ocrSummary`，其中 anchor 包含 `pageNo/chunkOrder/bbox` |
| P2-03 | 统一接口兼容策略 | P2-02 | 协议升级方案文档 + 兼容实现 | `/api/v1/search/kb` 可返回新协议；旧调用不阻断 |
| P2-03A | Anchor/BBox 规范与索引映射 | P2-02 | Anchor 字段规范 + `KbSegmentDocument` mapping 变更方案 | 文本 anchor 明确为 `pageNo + chunkOrder`，图片 `IMAGE_OCR_BLOCK` 支持 `bbox`（无坐标时可空） |

备注：
- 协议统一优先在 `/api/v1/search/kb` 落地；旧 `/api/v1/vision/*` 仅保兼容，不在本阶段删除。

---

## E2：Explain V2

| 卡片ID | 标题 | 依赖 | 输出 | 验收标准 |
|---|---|---|---|---|
| P2-04 | 扩展 explain 数据模型 | P2-02 | Explain V2 DTO | 文本可标注 `semantic/keyword/pageHit/chunkHit`；图片可标注 `vector/ocr/caption/tag` |
| P2-05 | explain 组装逻辑分型 | P2-04 | explain assembler / service | `TEXT_CHUNK` 与 `IMAGE_*` 命中来源判断正确 |
| P2-06 | explain 单测补齐 | P2-05 | 单元测试 | 至少覆盖 text-only / image-only / hybrid 三类命中 |

---

## E3：Rerank 统一

| 卡片ID | 标题 | 依赖 | 输出 | 验收标准 |
|---|---|---|---|---|
| P2-07 | 统一 rerank candidate 模型 | P2-02 | segment candidate 输入结构 | rerank 前后均可追踪 segmentId |
| P2-08 | KB 链路接入 rerank | P2-07 | `query -> recall -> rrf -> rerank -> assemble` | rerank 开/关可配置且不影响基础可用性 |
| P2-09 | rerank 参数配置化 | P2-08 | `topN/topK` 配置与默认值 | 参数生效，有回退默认值 |
| P2-10 | rerank 指标与回归验证 | P2-08 | 指标埋点 + 回归结果 | 输出耗时与命中变化，确保无明显退化 |

---

## E4：结果聚合展示（P1）

| 卡片ID | 标题 | 依赖 | 输出 | 验收标准 |
|---|---|---|---|---|
| P2-11 | asset 维度聚合逻辑 | P2-08 | 聚合器（按 assetId 聚合） | 同一 asset 多命中可聚合成一条主结果；图片 asset 聚合项包含缩略图与 OCR 摘要 |
| P2-12 | 聚合结果字段扩展 | P2-11 | `totalHits/topChunks/thumbnail/ocrSummary` 等字段 | 前端可展示“主命中+同资产其他命中”；图片卡片可展示缩略图 + OCR 摘要 |
| P2-13 | UI 最小适配 | P2-12 | Streamlit 结果页优化 | 可展开查看同 asset 下多个 chunk；图片命中展示缩略图与 OCR 摘要 |
| P2-13A | bbox 回传与最小展示 | P2-03A, P2-13 | 结果页 anchor 展示（含 bbox） | 图片命中可显示 bbox 文本信息（如 `[x1,y1,x2,y2]`），文本场景 bbox 不强制展示 |

---

## E5：离线评测

| 卡片ID | 标题 | 依赖 | 输出 | 验收标准 |
|---|---|---|---|---|
| P2-14 | 构建最小评测集 | 无 | 30-50 条 query + 标注 | text/image/hybrid 三类均覆盖（每类>=10） |
| P2-15 | 评测脚本实现 | P2-14 | 指标脚本 | 输出 Recall@1/3/5/10 + MRR 或 NDCG |
| P2-16 | 关键对比实验 | P2-15, P2-08 | 对比报告 | 至少两组：`text-only vs hybrid`、`rerank off vs on` |

---

## E6：简历版材料

| 卡片ID | 标题 | 依赖 | 输出 | 验收标准 |
|---|---|---|---|---|
| P2-17 | README V2 重写 | P2-16 | README | 5 分钟内可讲清定位/架构/链路/效果 |
| P2-18 | 架构图与链路图 | P2-17 | 2 张图（Mermaid/PlantUML） | 图文对应代码实现，无概念漂移 |
| P2-19 | 简历项目描述草稿 | P2-16 | 3-5 条可投递文案 | 含量化指标与技术取舍 |

---

## 4. 周度执行计划（与 4 周排期对齐）

## Week 9（约 15h）

- Must：P2-01, P2-02, P2-03, P2-03A
- 目标：统一协议落地并可兼容运行

## Week 10（约 15h）

- Must：P2-04, P2-05, P2-07, P2-08, P2-09
- 目标：explain V2 + rerank 统一链路打通

## Week 11（约 15h）

- Must：P2-14, P2-15, P2-16
- Should：P2-11, P2-12
- 目标：先拿到可量化结果，再做聚合增强

## Week 12（约 15h）

- Must：P2-17, P2-18, P2-19
- Should：P2-12, P2-13, P2-13A
- 目标：形成完整“可讲”材料，完成 M3

---

## 5. 依赖关系（简版）

```text
E1(统一协议)
  ├─> E2(explain V2)
  ├─> E3(rerank 统一)
  │      └─> E5(对比实验)
  └─> E4(聚合展示)

E5(评测结果)
  └─> E6(README/图/简历文案)
```

---

## 6. 验收口径（Phase 2 Done）

满足以下 7 条即视为 Phase 2 完成：

1. text + image 使用统一 search result 协议
2. 统一 anchor 协议可回传 `pageNo/chunkOrder/bbox`（文本可无 bbox）
3. explain 可解释文本与图片命中来源
4. rerank 能处理统一 segment candidate
5. 有 30-50 条评测集与指标输出
6. 至少两组对比实验有量化结论
7. README + 架构图 + 简历文案可直接用于面试讲述

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 协议升级影响旧调用 | 联调阻塞 | 兼容窗口 + 渐进切换 |
| rerank 接入后时延升高 | 体验下降 | topN 窗口控制 + 开关降级 |
| 评测标注成本高 | 进度延迟 | 先 30 条起步，后续扩展到 50 |
| 文档与实现不一致 | 面试叙事失真 | README/架构图与代码同周对齐 |
| 图片 bbox 数据缺失或格式不一 | anchor 展示不稳定 | 先统一 bbox 结构规范，缺失时置空并保留日志统计 |
| 缩略图 URL 不稳定或不可访问 | 图片结果展示受影响 | 统一 thumbnail 生成策略，失败时回退为 sourceRef/占位图 |
