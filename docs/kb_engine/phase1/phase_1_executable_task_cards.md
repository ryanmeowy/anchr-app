# Phase 1 可执行任务卡（Execution Backlog）

> 目标：在不破坏现有图片检索链路前提下，完成 Text + Image 统一检索 MVP。
> 原则：先新增、后切流；先跑通闭环、再做体验优化。

## 0. 约束与范围

- 不重写现有 `/api/v1/vision/*`、`/api/v1/image/*` 主链路。
- Phase 1 新增独立接口与独立索引，降低回归风险。
- 统一检索返回协议以 `segment` 为中心：`resultType/assetType/snippet/pageNo/score/explain`。

## 1. 任务总览（可执行）

| ID | 任务 | 优先级 | 依赖 | 预估 |
|---|---|---:|---|---:|
| P1-01 | 冻结接口契约（OpenAPI + DTO） | P0 | 无 | 0.5d |
| P1-02 | 统一 Segment 领域模型定义 | P0 | P1-01 | 0.5d |
| P1-03 | `kb_segment` 索引初始化与别名策略 | P0 | P1-02 | 1d |
| P1-04 | 文本上传接口（前端直传后批量提交） | P0 | P1-01 | 1d |
| P1-05 | 文本任务接入现有异步任务体系 | P0 | P1-04 | 1.5d |
| P1-06 | 文本解析抽象层（Parser Port） | P0 | P1-05 | 0.5d |
| P1-07 | PDF 页级解析器 | P0 | P1-06 | 1.5d |
| P1-08 | TXT/MD 解析器 | P0 | P1-06 | 1d |
| P1-09 | Chunk 切分器 | P0 | P1-07,P1-08 | 1d |
| P1-10 | 文本 Chunk 向量化与写入统一索引 | P0 | P1-09 | 1d |
| P1-11 | 图片 Segment 适配写入（OCR/CAPTION） | P0 | P1-03 | 1.5d |
| P1-12 | 统一检索服务（多路召回 + 融合） | P0 | P1-10,P1-11 | 2d |
| P1-13 | 统一结果组装与 explain 适配 | P0 | P1-12 | 1d |
| P1-14 | `/api/v1/search/kb` 接口落地 | P0 | P1-13 | 0.5d |
| P1-15 | Streamlit 最小结果页 | P1 | P1-14 | 1d |
| P1-16 | 回归与验收（10~20 query） | P1 | P1-15 | 1.5d |

总计：约 15~17 人天（单人约 3~4 周）。

---

## 2. 任务卡明细

### P1-01 冻结接口契约（OpenAPI + DTO）

**目标**

- 明确新增接口：
  - `POST /api/v1/ingestion/text-assets/batch-tasks`
  - `POST /api/v1/search/kb`
- 明确统一响应结构及错误码映射。

**输入/输出**

- 输入：现有 PRD 与 `phase_1_tech_solution.md`
- 输出：接口文档草案 + DTO 字段表 + 错误码表

**完成标准（DoD）**

- 字段命名、必填项、默认值、边界值清晰。
- 与前端联调字段无歧义。

---

### P1-02 统一 Segment 领域模型定义

**目标**

- 确认统一检索对象最小集合：
  - `TEXT_CHUNK`
  - `IMAGE_CAPTION`
  - `IMAGE_OCR_BLOCK`
- 确认统一文档字段：`segmentId/assetId/assetType/segmentType/title/contentText/ocrText/pageNo/chunkOrder/embedding/sourceRef/createdAt`。

**完成标准（DoD）**

- 字段含义与来源明确（文本/图片各自映射规则明确）。
- 形成映射矩阵（源字段 -> 统一字段）。

---

### P1-03 `kb_segment` 索引初始化与别名策略

**目标**

- 新建 `kb_segment` 索引 mapping/settings。
- 读写 alias 分离，支持后续平滑迁移。

**完成标准（DoD）**

- 本地与云配置均可创建索引。
- 维度配置与 embedding 服务维度一致。
- 支持 `text` 与 `knn` 检索字段。

**风险点**

- 向量维度不一致导致写入失败。

---

### P1-04 文本上传接口（前端直传后批量提交）

**目标**

- 支持前端直传 OSS 后，批量提交 `PDF/TXT/MD` 的 `objectKey/fileName/fileHash`，返回 `taskId/status`。

**完成标准（DoD）**

- MIME/后缀校验完整。
- 超限文件、空文件、非法类型有明确错误码。

---

### P1-05 文本任务接入现有异步任务体系

**目标**

- 复用现有 batch task 的状态与重试语义。
- 文本任务与图片任务共享任务可视化视图。

**完成标准（DoD）**

- 文本任务能 `PENDING/RUNNING/SUCCESS/FAILED` 正常流转。
- 单项重试与失败全量重试可用。

---

### P1-06 文本解析抽象层（Parser Port）

**目标**

- 统一解析接口，屏蔽不同格式差异。

**完成标准（DoD）**

- 上层仅依赖统一 `parse(file)` 语义。
- 解析结果统一到页/段结构。

---

### P1-07 PDF 页级解析器

**目标**

- 提供最小可用页级解析，保留 `pageNo`。

**完成标准（DoD）**

- 至少支持常见文本型 PDF。
- 返回页码正确，解析失败可重试。

**风险点**

- 扫描版 PDF 文本为空（Phase 1 可接受降级）。

---

### P1-08 TXT/MD 解析器

**目标**

- 提取纯文本，做基础标准化（空白/标题/段落）。

**完成标准（DoD）**

- TXT/MD 输出结构与 PDF 解析结果兼容。

---

### P1-09 Chunk 切分器

**目标**

- 生成满足 PRD 的 chunk 字段：`assetId/segmentId/title/pageNo/chunkText/chunkOrder`。

**完成标准（DoD）**

- 可配置 chunk 长度与 overlap。
- `chunkOrder` 连续且可复现。

---

### P1-10 文本 Chunk 向量化与写入统一索引

**目标**

- 文本 chunk 生成 embedding 并写入 `kb_segment`。

**完成标准（DoD）**

- 写入成功文档可被 text 与 vector 召回。
- embedding 失败、写入失败有任务级失败记录。

---

### P1-11 图片 Segment 适配写入（OCR/CAPTION）

**目标**

- 将现有图片元数据映射到统一 segment 文档：
  - OCR -> `IMAGE_OCR_BLOCK`
  - caption/summary/fileName -> `IMAGE_CAPTION`

**完成标准（DoD）**

- 图片结果仍可回到原图片展示。
- 不影响旧图片索引查询链路。

---

### P1-12 统一检索服务（多路召回 + 融合）

**目标**

- 单 query 同时召回 `TEXT_CHUNK + IMAGE_CAPTION + IMAGE_OCR_BLOCK`。
- 复用现有 RRF/rerank 机制。

**完成标准（DoD）**

- 统一召回与融合可观测（请求量、耗时、候选数）。
- 能稳定返回混合结果。

---

### P1-13 统一结果组装与 explain 适配

**目标**

- 返回统一协议：`resultType/assetType/snippet/pageNo/score/explain`。

**完成标准（DoD）**

- 文本命中可返回片段与页码。
- 图片命中可返回摘要并可跳转。

---

### P1-14 `/api/v1/search/kb` 接口落地

**目标**

- 提供独立知识库检索 API，避免干扰旧接口。

**完成标准（DoD）**

- 支持最小参数：`query/topK/limit`。
- 支持基础过滤（可选）。

---

### P1-15 Streamlit 最小结果页

**目标**

- 新增 KB 搜索页面，展示文本页码片段与图片摘要。

**完成标准（DoD）**

- 同一查询可见文本+图片混排结果。
- 字段展示与接口协议一致。

---

### P1-16 回归与验收（10~20 query）

**目标**

- 完成 Phase 1 端到端验收。

**完成标准（DoD）**

- 覆盖上传、任务状态、重试、检索、展示。
- 至少 3 个稳定 demo 脚本可复现。

---

## 3. 建议执行节奏

### 里程碑 M1（第 1 周）

- P1-01 ~ P1-06 完成（契约、模型、索引底座、任务接入框架）。

### 里程碑 M2（第 2 周）

- P1-07 ~ P1-11 完成（解析、chunk、文本入索引、图片适配）。

### 里程碑 M3（第 3 周）

- P1-12 ~ P1-16 完成（统一检索、接口、UI、验收）。

---

## 4. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| PDF 解析质量不稳定 | 文本召回质量波动 | Phase 1 仅承诺页级可用，先保证 pageNo 正确 |
| 向量维度不一致 | 写入失败 | 启动前校验索引维度与 embedding 维度 |
| 统一模型改造面过大 | 回归风险高 | 新增 `/search/kb` 与 `kb_segment`，不替换旧链路 |
| Rerank 成本/时延升高 | 响应变慢 | 首版限定 rerank 窗口，保留降级开关 |

---

## 5. 验收清单（可打勾）

- [ ] 上传 PDF/TXT/MD 成功生成文本任务
- [ ] 文本任务状态可查询、失败可重试
- [ ] 文本可解析并生成 chunk（含 pageNo/chunkOrder）
- [ ] 文本 chunk 成功写入统一索引
- [ ] 图片 segment 与文本 segment 可统一召回
- [ ] `/api/v1/search/kb` 返回统一协议
- [ ] Streamlit 页面可展示文本页码片段 + 图片摘要
- [ ] 10~20 条 query 验收通过
