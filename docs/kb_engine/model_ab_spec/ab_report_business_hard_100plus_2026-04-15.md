# Embedding A/B 测试报告（Business Hard 100+）

## 1. 测试概览

- 报告日期：`2026-04-15`
- 结果文件：`docs/lite_knowledge_search_engine_prd/model_ab_spec/ab_vector_report_business_hard_100plus.json`
- 测试集：`docs/lite_knowledge_search_engine_prd/model_ab_spec/test_cases.business_hard_100plus.json`
- 向量设置：稠密向量，`dimension=1024`
- 总用例：`108`
  - `text_to_text`: `24`
  - `text_to_image`: `84`
- 参评模型：
  - `qwen3-vl-embedding`
  - `doubao-embedding-vision`

## 2. 结论摘要

1. 两个模型都可用且稳定返回向量（`108/108` 成功，`vector_valid_rate=1.0`）。
2. 若只选一个默认模型，当前建议 **`doubao-embedding-vision`**：
   - 总体质量更高（Top1、MRR 均领先）
   - 延迟更低（平均和 P95 均更优）
3. 若允许分场景路由：
   - `text_to_text`：优先 Doubao（优势明显）
   - `text_to_image`：Qwen 质量略高，但速度更慢
4. 成本侧从官方公开标价看，两者单价同档，实际差异主要取决于输入 token 消耗结构。

## 3. 总体指标对比

| Provider                | Top1 Accuracy |          MRR | Vector Valid Rate | Avg Case Latency (ms) |
|-------------------------|--------------:|-------------:|------------------:|----------------------:|
| qwen3-vl-embedding      |      0.629630 |     0.742829 |          1.000000 |              6333.221 |
| doubao-embedding-vision |  **0.666667** | **0.771551** |          1.000000 |          **4959.380** |

解读：
- Doubao 在 Top1 上领先约 `+3.70` 个百分点，在 MRR 上领先约 `+2.87` 个百分点。
- Doubao 平均每个 case 快约 `1373.8 ms`。

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

## 4. 分类型结果

### 4.1 文搜文（text_to_text，24 cases）

| Provider                |         Top1 |          MRR | Avg Gap (pos-neg) | Avg Latency (ms) |     P95 (ms) |
|-------------------------|-------------:|-------------:|------------------:|-----------------:|-------------:|
| qwen3-vl-embedding      |     0.625000 |     0.719444 |          0.133716 |         5333.810 |     5610.931 |
| doubao-embedding-vision | **0.875000** | **0.923611** |          0.129916 |     **4777.501** | **5319.006** |

解读：
- 文搜文里 Doubao 质量优势很明显（Top1 +25pp，MRR +0.204）。

### 4.2 文搜图（text_to_image，84 cases）

| Provider                |         Top1 |          MRR | Avg Gap (pos-neg) | Avg Latency (ms) |     P95 (ms) |
|-------------------------|-------------:|-------------:|------------------:|-----------------:|-------------:|
| qwen3-vl-embedding      | **0.630952** | **0.749511** |      **0.128846** |         6618.767 |     6714.215 |
| doubao-embedding-vision |     0.607143 |     0.728105 |          0.102237 |     **5011.345** | **5965.902** |

解读：
- 文搜图质量上 Qwen 略优（Top1、MRR、Gap 都更高）。
- 文搜图时延上 Doubao 明显更优。

## 5. 稳定性与风险

- 技术稳定性：
  - Qwen：`case_success_count=108`，`case_error_count=0`
  - Doubao：`case_success_count=108`，`case_error_count=0`
- Top1 未命中数量：
  - Qwen：`40/108`
  - Doubao：`36/108`
- `negative gap` 用例（相关样本分数低于负样本均值）：
  - Qwen：`4` 个
  - Doubao：`6` 个

延迟风险：
- Qwen 出现 `1` 个超长尾样本：`biz_t2i_003_v02 = 36152 ms`（>36s）。
- Doubao 未出现 `>10s` 的 case，尾延迟更稳。

## 6. 典型差异样本
| Case ID             | Query            |   Qwen MRR |  Doubao MRR | Better |
|---------------------|------------------|-----------:|------------:|--------|
| biz_t2i_012_v01~v04 | 漏斗图              |      ~0.06 |         1.0 | Doubao |
| biz_t2t_004_v01~v04 | 向量健康验证           |       0.25 |         1.0 | Doubao |
| biz_t2t_006_v01~v04 | hard negative 设计 |  0.17~0.20 |         1.0 | Doubao |
| biz_t2i_013_v02     | 系统架构图            |        1.0 |      0.1429 | Qwen   |
| biz_t2i_021_v02     | 路线图/阶段节点         |        1.0 |        0.25 | Qwen   |

## 7. 选型建议

### 7.1 单模型默认选型

建议默认使用 **`doubao-embedding-vision`**。  
理由：在 100+ 样本下总体质量更高且时延更低，综合收益更稳。

### 7.2 按场景路由（可选优化）

如果允许双模型路由，建议：
1. `text_to_text` 走 Doubao（质量与延迟双优）。
2. `text_to_image` 可评估走 Qwen（质量略优），但需确认可接受更高时延与偶发长尾。

## 8. 成本对比（官方定价）

### 8.1 官方价格口径

| Provider | 文本输入单价 | 图片/视频输入单价 | 计费说明 |
|---|---:|---:|---|
| qwen3-vl-embedding | 0.0007 元/千 Token | 0.0018 元/千 Token | 按输入 Token 计费，输出不计费 |
| doubao-embedding-vision | 0.0007 元/千 Token | 0.0018 元/千 Token | 按输入 Token 计费 |

解读：
- 两个模型在公开文档中的 embedding 单价属于同一档位。
- 在相同请求结构下，理论单位调用成本接近；差异主要来自实际输入 token 数。

### 8.2 本次 100+ 测试的成本结论

1. 本测试结果 JSON 未记录逐请求 token 用量，无法精确回放绝对账单金额。
2. 单价相同前提下，成本优化重点应放在：
   - query 文本长度控制
   - 图片/视频输入比例控制
   - 冗余候选向量生成次数控制
3. 因为本轮 Doubao 在质量/时延综合上更优，且单价同档，默认选 Doubao 不会带来“单价溢价”风险。

### 8.3 估算公式（可用于预算）

- 单次请求成本（估算）：
  - `cost ~= text_tokens/1000*0.0007 + image_video_tokens/1000*0.0018`
- 周期总成本（估算）：
  - `total_cost ~= Σ(每次请求成本)`

### 8.4 官方参考链接

- 阿里云百炼模型价格：<https://help.aliyun.com/zh/model-studio/model-pricing>
- 阿里云百炼向量化（含 qwen3-vl-embedding）：<https://help.aliyun.com/zh/model-studio/embedding>
- 火山引擎向量库计费（含 doubao-embedding-vision）：<https://www.volcengine.com/docs/84313/1414459?lang=zh>
- 火山方舟计费说明：<https://www.volcengine.com/docs/82379/2121998>
