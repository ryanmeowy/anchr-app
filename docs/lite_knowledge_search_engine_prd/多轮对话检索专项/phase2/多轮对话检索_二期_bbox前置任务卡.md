# 多轮对话检索专项（二期）bbox 前置任务卡

依据：`多轮对话检索_二期_bbox前置PRD.md`  
更新时间：2026-05-06  
状态：Confirmed

## 1. 阶段目标

在 Phase2 主链路启动前，补齐图片 OCR bbox 入库与查询能力：

1. 建立结构化 bbox 协议（`x/y/width/height/unit`），废弃语义不清的数组格式。
2. 传统 OCR API 接入，获取 paragraph 级坐标与文本。
3. LLM OCR 对文本进行语义增强，受相似度阈值约束避免文本漂移。
4. 一张图片拆分为多个 `IMAGE_OCR_BLOCK` segment，每个 OCR paragraph 一个 segment，各自携带 bbox。
5. `KbSegmentDocument` bbox 字段重构为结构化 object，新增 `imageWidth/imageHeight`。
6. 搜索结果、resultCards、preview API 透传结构化 bbox。
7. 前端图片预览可按 bbox 在原图上绘制命中框。
8. bbox 缺失或无效时安全降级，并记录质量指标。

## 2. 边界原则

### 2.1 Must

1. bbox 协议定稿：结构化对象格式，含 `x/y/width/height/unit` 和 `imageWidth/imageHeight`。
2. 传统 OCR API 接入验证并完成坐标归一化适配。
3. ES mapping 重构：`bbox` 从单值 `integer` 改为 `object`（`x/y/width/height/unit`），新增 `imageWidth/imageHeight` integer 字段。
4. `KbSegmentDocument`、`Segment`、`KbSearchResultDTO.Anchor` 同步变更为结构化 bbox。
5. OCR 混合模式实现：传统 OCR（bbox + text）→ LLM OCR（文本增强）→ 文本对齐约束。
6. 一图多 segment：使用传统 OCR API 原生 paragraph 层级结构作为 segment 粒度，每个 paragraph 对应一个 `IMAGE_OCR_BLOCK` segment，bbox 取 paragraph 内所有 words 的外接矩形。
7. `IMAGE_CAPTION` segment 保持唯一，持有图片向量用于向量检索。
8. `imageWidth/imageHeight` 来源：OCR API 优先 → 图片 header 兜底 → 无则不写。
9. preview API 可按 `segmentId` 返回结构化 bbox anchor。
10. 前端图片预览可按原图尺寸映射 bbox 到渲染尺寸绘制命中框。
11. bbox 缺失、越界或尺寸异常时安全降级：展示原图 + OCR 文本 + 提示，不绘制错误框。
12. 6 个入库侧 bbox/OCR 质量指标写入 Micrometer。
13. 测试覆盖有 bbox、无 bbox、越界 bbox、尺寸缺失四类场景；10 张样例图片 bbox 定位成功率 ≥ 90%。

### 2.2 Out of Scope

1. 人工标注、标注编辑、协同批注。
2. word 级精准高亮。
3. 多 bbox 合并策略的复杂排序。
4. 图片裁剪图生成。
5. bbox 反向修正 OCR 结果。
6. per-paragraph 向量嵌入（OCR paragraph segment 不含 embedding，仅靠文本检索命中）。
7. 历史数据回填或迁移（无历史数据）。

## 3. 任务卡明细

| 卡片ID | 状态 | 标题 | 依赖 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| B2-01 | DONE | bbox 协议定稿 | 无 | bbox 结构化字段定义、unit/坐标系说明、imageWidth/Height 来源策略、降级规则文档 | 明确 `x/y/width/height/unit: PIXEL` 作为协议标准；`imageWidth/imageHeight` 有明确获取来源与兜底策略；不再对外暴露 `List<Integer>` |
| B2-02 | DONE | ES mapping 重构 + DTO/Model 变更 | B2-01 | `es-kb-segment-mapping.json` 更新、`KbSegmentDocument` bbox 字段改为 `Bbox` 对象、新增 `imageWidth/imageHeight`、`Segment` 模型同步、`KbSearchResultDTO.Anchor.bbox/imageWidth/imageHeight` 改为结构化 anchor 字段 | ES bbox mapping 从 `integer` 改为 `object{x,y,width,height,unit}`；`imageWidth/imageHeight` 为 `integer`；所有引用 `List<Integer> bbox` 的代码适配完成；编译通过 |
| B2-03 | DONE | 传统 OCR API 接入验证 | 无 | OCR provider 选型结论、PoC 代码、坐标解析适配逻辑、结构化 OCR 返回模型（`OcrStructuredResult`：paragraphs list + imageWidth/Height） | 选定阿里云 `RecognizeAdvanced`；PoC 代码可调用传统 OCR 并解析 paragraph/text/word bbox；四点坐标可安全归一到外接矩形；`OcrStructuredResult` 模型可承载 paragraph、bbox、原图尺寸；返回格式不稳定时有降级 |
| B2-04 | TODO | OCR 混合模式入库 + 质量指标 | B2-02, B2-03 | `IngestionOcrPort` 扩展、`OcrStructuredResult` 模型、传统 OCR 适配器、逐 paragraph LLM OCR 增强逻辑、文本对齐约束、`ImageSegmentIndexWriter` 改造为 paragraph 级多 segment 写入、入库侧 Micrometer 指标埋点（`write_success/missing/out_of_bounds/image_size_missing/text_drift/paragraph_capped`） | 传统 OCR → 逐 paragraph LLM OCR 增强链路打通；正常路径按 OCR API 原生 paragraph 层级拆分 segment（N = paragraph 数），每个 segment 的 bbox 取 paragraph 内 words 外接矩形；paragraph 数 > 30 时激活兜底合并并记录 `paragraph_capped`；文本相似度低于阈值时保留原文并记录 `text_drift` 指标；每个 segment 携带 bbox + imageWidth/Height；`IMAGE_CAPTION` 保持唯一且持有向量；6 个入库侧质量指标可被 Prometheus 采集 |
| B2-05 | TODO | bbox 查询与 DTO 透传 | B2-04 | ES segment 按 ID 查询能力、`ResultHitDTO.anchor` 更新、`PreviewAnchorDTO` 更新、preview API 返回结构化 bbox、imageWidth、imageHeight | 按 `segmentId` 可查询到 segment 的 bbox、imageWidth、imageHeight；搜索结果和 preview API 返回的 anchor 结构一致（同一套 bbox 对象和原图尺寸）；图片命中卡片可展示 anchor 信息 |
| B2-06 | TODO | 图片预览 bbox 映射 | B2-05 | 前端 bbox 坐标映射逻辑、命中框绘制、降级展示 | 有效 bbox 按 `renderedWidth/imageWidth` 比例映射后可正确绘制命中框；bbox 缺失/越界/尺寸异常时不画框，展示原图 + OCR 文本 + 提示 |
| B2-07 | TODO | bbox 回归与样例验收 | B2-06 | 单元测试/接口测试、10 张样例图片定位记录 | 覆盖有 bbox/无 bbox/越界 bbox/尺寸缺失 4 类场景；10 张样例定位成功率 ≥ 90%；单测通过 |

## 4. 依赖关系

```text
B2-01 bbox 协议定稿
  ├─> B2-02 ES mapping 重构 + DTO/Model 变更
  │     └─> B2-04 OCR 混合模式入库
  │           └─> B2-05 bbox 查询与 DTO 透传
  │                 └─> B2-06 图片预览 bbox 映射
  │                       └─> B2-07 bbox 回归与样例验收
  └─> B2-03 传统 OCR API 接入验证 ────┘
                                        （B2-02 与 B2-03 可并行）
```

## 5. 排期建议

总计：**8~10 天**（Phase2 启动前完成）

### 第 1-2 天：协议 + 基础设施（可并行）

| 卡片 | 工作 |
|------|------|
| B2-01 | bbox 协议文档定稿，对齐前后端 |
| B2-03 | 传统 OCR API PoC，验证坐标返回格式与覆盖率 |

### 第 2-4 天：ES mapping + DTO + OCR 入库

| 卡片 | 工作 |
|------|------|
| B2-02 | ES mapping 更新、`KbSegmentDocument`/`Segment`/`KbSearchResultDTO` 适配 |
| B2-04 | OCR 混合模式链路打通，`ImageSegmentIndexWriter` 改造为多 segment 写入 |

> B2-02 和 B2-04 有依赖关系，B2-02 完成后 B2-04 才能联调入库。B2-02 工期 1 天，完成后立即启动 B2-04（2~3 天）。

### 第 5-7 天：查询透传 + 前端映射

| 卡片 | 工作 |
|------|------|
| B2-05 | preview API + ResultHit/PreviewAnchor DTO 透传结构化 bbox |
| B2-06 | 前端 bbox 映射绘制 + 降级逻辑 |

> B2-05 和 B2-06 有依赖关系。B2-05 完成后 B2-06 可启动。

### 第 8 天：验收

| 卡片 | 工作 |
|------|------|
| B2-07 | 四类场景测试 + 10 张样例验收 + 定位成功率记录 |

## 6. E0B Done 标准

满足以下条件视为 bbox 前置能力完成：

1. bbox 协议文档定稿，`x/y/width/height/unit/imageWidth/imageHeight` 字段语义无歧义。
2. ES `kb_segment` 索引 bbox 字段为结构化 object，`imageWidth/imageHeight` 字段可用。
3. `KbSegmentDocument`、`Segment`、`KbSearchResultDTO.Anchor` 均使用结构化 bbox，不再暴露 `List<Integer>`。
4. 传统 OCR API 可正常返回 paragraph 级坐标与文本；正常路径无需自行合并或拆分，超过 30 个 paragraph 时只执行安全兜底合并。
5. 图片入库链路可按 OCR API 原生 paragraph 层级产出 N 个 `IMAGE_OCR_BLOCK` segment（N = paragraph 数，超过安全上限时为合并后数量），每个携带 bbox 与 imageWidth/Height。
6. LLM OCR 增强输出与原始 paragraph 文本可通过相似度阈值对齐，漂移 paragraph 被记录。
7. preview API 可按 `segmentId` 返回结构化 bbox anchor。
8. 前端图片预览可基于有效 bbox 绘制命中框。
9. bbox 缺失、越界或尺寸异常时安全降级：展示原图 + OCR 文本 + 提示，不绘制错误框。
10. 6 个 Micrometer 入库侧 bbox/OCR 质量指标可被采集。
11. 10 张样例图片 bbox 定位成功率 ≥ 90%。
12. 四类测试场景（有 bbox / 无 bbox / 越界 bbox / 尺寸缺失）用例通过。

## 7. 对二期主任务卡的联动影响

| 二期任务卡卡片 | 影响 |
|---------------|------|
| C2-02（预览 anchor 规范） | B2-01 定稿后可作为图片 anchor 的直接输入 |
| C2-08（新增 preview 查询模型） | `PreviewSegmentDTO.anchor` 直接引用 B2-05 的结构化 bbox |
| C2-09（实现 segment 预览接口） | 按 segmentId 查 bbox → B2-05 提供 |
| C2-09A（Segment 元数据稳定查询） | B2-05 提供 ES segment lookup 能力 |
| C2-13（预览后端测试） | B2-07 的图片 bbox 用例可直接复用 |
| C2-28（IMAGE bbox 预览定位） | B2-06 的前端映射逻辑为直接前置，验收标准对齐 |
| C2-36（定位成功率验收集） | B2-07 的样例数据可直接纳入 |

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 传统 OCR API 坐标返回不稳定（不同图片返回不同精度） | bbox 无法统一映射 | PoC 阶段用 20+ 张异质图片验证；直接使用 API 原生 paragraph 层级，取 paragraph 内 words 外接矩形 |
| 传统 OCR API 不返回 imageWidth/imageHeight | 无原图尺寸，无法比例映射 | 回退到读取图片 header 获取尺寸（`ImageIO.read()`） |
| LLM 增强文本与传统 OCR paragraph 文本大幅漂移 | bbox 位置与展示文本不一致 | 逐 paragraph 计算文本相似度（如 Levenshtein ratio < 0.7 时保留原文）；记录 `text_drift` 指标 |
| ES bbox mapping 为 breaking change | 旧索引不可兼容 | 新版本索引 + alias 切换；旧索引保留可回滚；无历史数据无需迁移 |
| 传统 OCR 与 LLM OCR 分属不同 API（阿里云文档 OCR vs DashScope） | 配置复杂度增加 | 统一在 `application-cloud-aliyun.yaml` 中管理；PoC 阶段确认两套 API 可共存 |
| OCR API 对密集排版图片返回 paragraph 数超过 30（低概率） | 索引写入压力增大 | 一线规则沿用 API 原生 paragraph 不做拆分合并；超过 30 时激活兜底：合并相邻 paragraph 直到 ≤ 30，记录 `smartvision.ingestion.ocr.paragraph_capped` |
