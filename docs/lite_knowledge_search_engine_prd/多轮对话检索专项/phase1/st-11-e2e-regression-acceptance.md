# ST-11 E2E 联调与回归验收

更新时间：2026-04-30

## 1. 目标

- 覆盖多轮对话检索 P0 的关键回归场景。
- 重点验证四类内容：多轮追问、降级兜底、接口联调、性能基线口径。

## 2. 自动化回归用例

### 2.1 会话业务闭环（Service 层）

- `ConversationServiceImplTest#createMessage_shouldSupportMultiTurnAndPersistTrace`
  - 验证点：
    - 同一会话连续提问可成功写入 turn。
    - 第二轮可返回 rewrite 后 query。
    - 回答返回 citations。
    - turn 中 `retrievalTraceJson` 含 rewrite/retrieval/answer 关键字段。
- `ConversationServiceImplTest#createMessage_shouldFallbackWhenEvidenceIsEmpty`
  - 验证点：
    - 无证据场景返回降级回答，不编造内容。
    - citations 为空时触发 `answer.citation.empty.count`。
    - `retrievalTraceJson` 记录 `answerFallback=true` 与 fallback reason。

### 2.2 接口联调回归（Controller 层）

- `ConversationApiControllerTest#createSession_shouldReturnResultEnvelope`
  - 验证会话创建接口响应包结构。
- `ConversationApiControllerTest#createMessage_shouldReturnConversationPayload`
  - 验证提问接口返回 `turnId` 与 `retrievalTrace`。
- `ConversationApiControllerTest#listMessages_shouldReturnTurns`
  - 验证历史消息接口响应结构。
- `ConversationApiControllerTest#createMessage_shouldRejectWhenQueryMissing`
  - 验证参数校验错误路径（`query` 缺失 -> `400`）。
- `ConversationApiControllerTest#listMessages_shouldPassQueryParameters`
  - 验证 `limit/beforeTurnId` 查询参数透传与响应结构。

## 3. 性能基线口径（P0）

- PRD 目标：单轮对话响应 `3s ~ 6s`。
- ST-11 输出：给出联调环境统一压测口径，避免后续评估口径不一致。

### 3.1 联调环境建议步骤

1. 创建会话：`POST /api/conversations`
2. 连续发送 20 次提问：`POST /api/conversations/{sessionId}/messages`
3. 记录每次响应耗时（客户端 wall time）
4. 统计 `p50/p90/p95/max`

### 3.2 验收阈值建议

- `p95 <= 6s`
- `max <= 8s`
- 超阈值时优先排查 rewrite/answer 模型耗时与 topK 配置

## 4. PRD 验收清单映射

- [x] 1. 可创建会话并连续提问
- [x] 2. 第二轮问题可继承上下文完成 rewrite
- [x] 3. 复用现有 search 底盘
- [x] 4. 回答附带 citations
- [x] 5. 证据不足时不编造答案
- [x] 6. 每轮可追踪 rewrite/retrieval/citations 关键字段
- [x] 7. 会话层新增指标可观测（通过回归用例覆盖关键计数器）
