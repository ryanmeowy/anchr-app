# Embedding A/B 测试报告（Business Hard Expanded）

## 1. 测试概览

- 报告日期：`2026-04-15`
- 结果文件：`docs/lite_knowledge_search_engine_prd/model_ab_spec/ab_vector_report_business_hard_expanded.json`
- 测试集：`docs/lite_knowledge_search_engine_prd/model_ab_spec/test_cases.business_hard_expanded.json`
- 向量设置：稠密向量，`dimension=1024`
- 总用例：`27`
  - `text_to_text`: `6`
  - `text_to_image`: `21`
- 参评模型：
  - `qwen3-vl-embedding`
  - `doubao-embedding-vision`

## 2. 结论摘要

- 两个模型技术稳定性都达标：
  - `case_success_count = 27/27`
  - `vector_valid_rate = 1.0`
- 在本次 expanded hard 集上，**Doubao 总体更优**：
  - 质量更高（`Top1`、`MRR` 均领先）
  - 延迟更低（平均与 P95 都更好）
- 两边共同风险：
  - 对“结构相似图形语义”（时间线/架构图/漏斗图等）仍存在混淆。

## 3. 总体指标对比

| Provider                | Top1 Accuracy |          MRR | Vector Valid Rate | Avg Case Latency (ms) |
|-------------------------|--------------:|-------------:|------------------:|----------------------:|
| qwen3-vl-embedding      |      0.666667 |     0.764043 |          1.000000 |              6223.815 |
| doubao-embedding-vision |  **0.703704** | **0.800816** |          1.000000 |          **5076.374** |

解读：
- Doubao 在 Top1 上领先约 `+3.70` 个百分点，在 MRR 上领先约 `+3.68` 个百分点。
- Doubao 平均每个 case 快约 `1147 ms`。

## 3.1 指标含义说明

- `Top1 Accuracy`
  - 含义：每个 query 排名第 1 的候选是否相关，取整体平均比例。
  - 方向：越高越好。
- `MRR`（Mean Reciprocal Rank）
  - 含义：关注“第一个相关结果”的位置，单个 query 记为 `1/rank`，再对所有 query 求平均。
  - 方向：越高越好，且对头部排序更敏感。
- `Vector Valid Rate`
  - 含义：向量健康检查通过率（维度正确、无 NaN/Inf、非零向量）。
  - 方向：越高越好，理想值为 `1.0`。
- `Avg Case Latency (ms)`
  - 含义：单个测试 case 的平均总耗时（包含 query 向量、候选向量、相似度计算流程）。
  - 方向：越低越好。
- `P95 Latency (ms)`
  - 含义：95 分位耗时，用于衡量慢请求尾部表现。
  - 方向：越低越好。
- `Avg Gap (pos-neg)`
  - 含义：正样本平均分减去负样本平均分，衡量“可分离性”。
  - 方向：越大越好，越大表示相关与不相关更容易被拉开。
- `negative gap`
  - 含义：当 `pos-neg < 0` 时，表示负样本平均分高于正样本平均分，是明显错排信号。
  - 方向：数量越少越好（理想为 0）。
- `Top1 miss count`
  - 含义：Top1 未命中的 case 数量。
  - 方向：越少越好。

## 4. 分类型指标

### 4.1 文搜文（text_to_text，6 cases）

| Provider                |         Top1 |          MRR | Avg Gap (pos-neg) | Avg Latency (ms) |     P95 (ms) |
|-------------------------|-------------:|-------------:|------------------:|-----------------:|-------------:|
| qwen3-vl-embedding      |     0.666667 |     0.741667 |          0.136849 |         5456.959 |     5654.596 |
| doubao-embedding-vision | **0.833333** | **0.888889** |          0.135768 |     **4783.266** | **5315.251** |

解读：
- 本轮文搜文里，Doubao 优势明显。

### 4.2 文搜图（text_to_image，21 cases）

| Provider                |     Top1 |          MRR | Avg Gap (pos-neg) | Avg Latency (ms) |     P95 (ms) |
|-------------------------|---------:|-------------:|------------------:|-----------------:|-------------:|
| qwen3-vl-embedding      | 0.666667 |     0.770437 |      **0.158665** |         6442.917 |     7415.515 |
| doubao-embedding-vision | 0.666667 | **0.775652** |          0.124079 |     **5160.119** | **5793.316** |

解读：
- 文搜图质量接近（Top1 持平，MRR 接近）。
- Qwen 的平均分数间隔（`avg_gap`）更大，但 Doubao 仍在最终排序质量（MRR）和延迟上占优。

## 5. 稳定性与风险

- 技术稳定性：
  - Qwen：`case_success_count=108`，`case_error_count=0`
  - Doubao：`case_success_count=108`，`case_error_count=0`
- Top1 未命中数量：
  - Qwen: `9/27`
  - Doubao: `8/27`
- `negative gap` 用例（相关样本分数低于负样本均值）：
  - Qwen: `1` 个（`biz_t2i_012`, 漏斗图）
  - Doubao: `1` 个（`biz_t2i_015`, 时间线）

按 case 的 MRR 胜负：
- Qwen wins: `6`
- Doubao wins: `5`
- Tie: `16`

解读：
- 大多数 case 是平局，整体差距主要来自少数“高难样本”的大分差。

## 6. 分差最大的样本（按 MRR）

| Case ID     | Query                             | Qwen MRR | Doubao MRR | Better |
|-------------|-----------------------------------|---------:|-----------:|--------|
| biz_t2i_012 | 漏斗图，上宽下窄的分层转化结构                   |   0.0625 |     1.0000 | Doubao |
| biz_t2t_006 | hard negative 用例怎么设计才真正有区分度？      |   0.2000 |     1.0000 | Doubao |
| biz_t2t_004 | 怎么验证 embedding 输出向量本身是健康的？        |   0.2500 |     1.0000 | Doubao |
| biz_t2t_003 | 文搜图里，用户说“差不多像业务流程图那种”，该怎么提升召回鲁棒性？ |   1.0000 |     0.3333 | Qwen   |
| biz_t2i_019 | 仪表盘卡片，多块指标面板                      |   1.0000 |     0.5000 | Qwen   |
| biz_t2i_008 | 热力图，矩阵格子通过颜色深浅表达数值                |   0.5000 |     1.0000 | Doubao |

## 7. 选型建议

### 推荐默认模型

当前阶段建议默认选择 **`doubao-embedding-vision`**。

### 选型理由

1. 本数据集下整体质量更好（`Top1`、`MRR` 均领先）。
2. 延迟表现更好（平均延迟与 P95 都更低）。
3. 技术稳定性一致（两者均 `27/27` 成功，且 `vector_valid_rate=1.0`）。

### 风险与注意点

1. 两个模型在结构相似图形（时间线/架构图/漏斗图）上仍会互相混淆。
2. 由于平局样本较多（`16/27`），最终定型建议结合更大规模数据并做多轮重复实验。

## 8. 下一步评估建议

1. 扩充到 `>=100` 条混合业务 query，加入更真实的噪声表达。
2. 按视觉结构维护 hard-negative 池（timeline/funnel/architecture/swimlane/heatmap）。
3. 连续跑 `3` 轮，输出 Top1/MRR/Latency 的均值与方差。
4. 按 query 意图切片汇报（明确图形词 vs 会话式模糊表达）。
