# ST-15 无证据策略优化

更新时间：2026-04-30

## 1. 目标

- 优化“无证据”判定与兜底表达，减少弱证据场景下的误答。
- 在兜底回答中提供改写建议与重试引导。

## 2. 策略实现

### 2.1 阈值策略

在 `AnswerGenerationServiceImpl` 中新增无证据判定分支：

1. `no_grounding_segment`：无可用 grounding 片段。
2. `evidence_too_short`：证据总长度不足（< 80 字符）且证据条数 < 2。
3. `low_retrieval_score`：最高检索分数过低（< 0.12）且证据条数 < 2。

触发任一条件时，直接走无证据兜底，不进入模型生成。

### 2.2 提示语模板

统一使用模板返回：

- 明确“未找到足够内容支持该问题”
- 输出“建议改写检索问题：{rewrittenQuery/userQuery}”
- 输出三条重试指引（补实体、加限定词、拆问题）

### 2.3 可观测与追踪

- `fallbackReason` 细化为：
  - `no_evidence_no_grounding_segment`
  - `no_evidence_evidence_too_short`
  - `no_evidence_low_retrieval_score`
- 现有 trace 字段 `answerFallback/answerFallbackReason` 可直接承载。

## 3. 回归验证

- `AnswerGenerationServiceImplTest`
  - 覆盖三条无证据判定路径。
  - 校验模板中包含改写建议与重试引导文本。
- 既有 `ConversationServiceImplTest`、`ConversationApiControllerTest` 回归通过。
