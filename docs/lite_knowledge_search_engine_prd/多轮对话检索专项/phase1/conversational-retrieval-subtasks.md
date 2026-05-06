# 子任务清单：多轮对话检索专项

更新时间：2026-04-30

## 1. 目标

- 基于当前 `dev/ryan` 分支能力，交付“多轮对话检索 MVP”。
- 满足 PRD P0 验收口径：会话、多轮 rewrite、检索复用、受控回答、引用溯源、可观测。
- 预留 P1 增强项任务位：推荐追问、会话标题、trace 展示、无证据策略优化。

## 2. 交付边界

### P0（本期必交）

- 会话模型与会话接口。
- 会话内提问闭环：`用户问题 -> rewrite -> 检索 -> answer -> citations -> turn 持久化`。
- 复用现有 `UnifiedSearchService`，不重造检索底盘。
- AI 不可用时可降级为检索结果返回，不编造答案。
- 会话层新增核心指标并可观测。
- Streamlit 提供可联调的对话页 MVP。

### P1（次期增强）

- 推荐追问。
- 会话标题自动生成。
- retrieval trace UI 展示增强。
- 无证据兜底策略优化（阈值、表达、引导问法）。

### P2（不在本任务卡实现）

- 会话内结果重用。
- 对话级缓存。
- 复杂 query decomposition。
- 多轮主题漂移检测。

## 3. 子任务拆分（按执行顺序）

| 序号 | 子任务 | 优先级 | 状态 | 主要产出 | 依赖 | 预估 |
|---|---|---|---|---|---|---|
| ST-01 | 冻结对话接口契约与DTO | P0 | 已完成 | `POST/GET /api/conversations` 与 `POST/GET /messages` 请求响应字段、错误码、边界约束 | 无 | 0.5 天 |
| ST-02 | 会话领域模型与仓储 | P0 | 已完成 | `ConversationSession`、`ConversationTurn`、Repository（含最近 N 轮读取） | ST-01 | 1.0 天 |
| ST-03 | 会话应用服务与API | P0 | 已完成 | 创建会话、查询会话、查询历史消息、写入 turn 的应用层服务与控制器 | ST-02 | 1.0 天 |
| ST-04 | Query Rewrite 服务（MVP） | P0 | 已完成 | 最近 3~5 轮上下文改写、实体/模态提取、confidence 输出、fallback 机制 | ST-03 | 2.0 天 |
| ST-05 | 对话检索编排器 | P0 | 已完成 | `ConversationRetrievalOrchestrator`，调用 `UnifiedSearchService`，输出 topK + explain + grouped results | ST-04 | 1.0 天 |
| ST-06 | 引用映射与溯源模型 | P0 | 已完成 | `Citation` 模型、`KbSearchResultDTO -> Citation` 映射、回答中引用编号关联 | ST-05 | 1.0 天 |
| ST-07 | 回答生成服务（Grounded） | P0 | 已完成 | top3~5 证据注入生成、结构化回答模板、无证据兜底、LLM 不可用降级 | ST-06 | 2.5 天 |
| ST-08 | 会话消息闭环编排 | P0 | 已完成 | `POST /messages` 全链路：turn 创建、rewrite、retrieval、answer、citations、turn 落库 | ST-07 | 1.0 天 |
| ST-09 | 监控指标与追踪字段 | P0 | 已完成 | conversation/rewrite/retrieval/answer/citation/no-evidence 指标与 trace 字段落库 | ST-08 | 1.0 天 |
| ST-10 | Streamlit 对话页 MVP | P0 | 已完成 | 会话列表、提问输入、回答渲染、引用展示、错误与降级态展示 | ST-08 | 1.5 天 |
| ST-11 | E2E 联调与回归验收 | P0 | 已完成 | 多轮追问验证、降级验证、性能基线、PRD 验收清单打勾 | ST-09,ST-10 | 2.0 天 |
| ST-12 | 推荐追问能力 | P1 | 已完成 | `suggestedQuestions` 2~4 条，证据相关性约束 | ST-11 | 1.0 天 |
| ST-13 | 会话标题自动生成 | P1 | 已完成 | 首轮问题或摘要自动命名会话标题 | ST-11 | 0.5 天 |
| ST-14 | retrieval trace UI 展示 | P1 | 已完成 | Streamlit 展示 rewrite reason、topK 摘要、explain 信息 | ST-11 | 0.5 天 |
| ST-15 | 无证据策略优化 | P1 | 已完成 | 阈值策略、提示语模板、改写建议与重试引导 | ST-11 | 1.0 天 |

## 4. 依赖顺序（关键路径）

1. ST-01 -> ST-02 -> ST-03
2. ST-03 -> ST-04 -> ST-05 -> ST-06 -> ST-07 -> ST-08
3. ST-08 -> ST-09
4. ST-08 -> ST-10
5. ST-09 + ST-10 -> ST-11
6. ST-11 -> ST-12/13/14/15

## 5. 排期建议（单人）

### Week 1（5 天）

- ST-01, ST-02, ST-03, ST-04（接口与会话骨架 + rewrite MVP）。

### Week 2（5 天）

- ST-05, ST-06, ST-07, ST-08（检索编排 + 回答与引用 + 消息闭环）。

### Week 3（5 天）

- ST-09, ST-10, ST-11（指标、Streamlit、E2E 回归）。

### Week 4（2~3 天，可选）

- ST-12, ST-13, ST-14, ST-15（P1 增强）。

## 6. 工作量结论

- P0：约 **14.5 ~ 15.5 人天**（可并行后可压缩至 12~13 人天）。
- P1：约 **3.0 人天**。
- 全量（P0+P1）：约 **17.5 ~ 18.5 人天**。

说明：当前估算较《多轮对话检索_工作量评估.md》收敛，主要原因是本次任务卡按“复用现有链路 + MVP 先行”压缩了探索项与重复建设项。

## 7. 验收标准（P0）

1. 可创建会话并在同一会话连续提问。
2. 第二轮及后续问题可基于最近 3~5 轮上下文完成 rewrite。
3. 检索链路复用现有 `UnifiedSearchService`，可返回 text+image 命中。
4. 回答输出包含 citations，且可追溯到具体 source 元信息。
5. 证据不足时不编造答案，返回“证据不足 + 相关片段”。
6. 每轮可追踪 rewrite、retrieval、answer、citations 关键字段。
7. conversation/rewrite/retrieval/answer/citation 指标可观测。

## 8. 风险与对策

1. 风险：rewrite 偏离用户原意。
- 对策：增加 rewrite reason 与 confidence；低置信度时保守回落到原 query。

2. 风险：生成回答出现幻觉。
- 对策：强制基于 topK 证据生成；证据不足直接兜底，不输出强结论。

3. 风险：整体时延超过目标（3~6s）。
- 对策：限制 rewrite 与 answer 模型超时、限制 grounding 片段数（top3~5）。

4. 风险：联调阶段字段不一致。
- 对策：先冻结 ST-01 接口契约，后续实现严格对齐 DTO。
