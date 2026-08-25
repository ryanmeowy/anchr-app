# Agent RAG 实现说明

本文记录 `anchr-app` 当前的 Agent RAG 执行路径、状态、限制和失败语义。内容以源码、运行配置和数据库迁移为准，不包含规划中的能力。

## 1. 执行路径

消息请求在 Agent 和传统流程中择一执行：

```mermaid
flowchart LR
    A["用户消息"] --> B{"请求和服务端都开启 Agent?"}
    B -- "是" --> C["Agent 工具循环"]
    B -- "否" --> D["传统路由"]
    C --> E["保存 Turn"]
    D --> E
    E --> F["同步 JSON 或 SSE 响应"]
```

关键约束：

- 模型决定工具调用；权限、预算、证据、引用和终态由后端校验。
- 传统流程先路由 `CHAT`、`OTHER` 和 `KB_QUERY`，仅 `KB_QUERY` 执行 RAG。
- Agent 只能引用当前 Run 注册的 Segment。
- Elasticsearch 可同时保留多个 generation，查询只接受资产的 active generation。
- SSE 断开不取消执行；Turn 仍会落库，客户端通过 Run、Task 或消息接口恢复状态。
- 文档总结使用异步 Task，不在同步 Run 中读取全文。

## 2. API 与请求范围

### 2.1 API

| 场景 | API | 行为 |
| --- | --- | --- |
| 同步问答 | `POST /api/v1/conversations/{sessionId}/messages` | 执行完成并保存 Turn 后返回 JSON |
| 消息 SSE | `POST /api/v1/conversations/{sessionId}/messages/stream` | 实时发送过程事件和安全的模型答案增量，终态使用完整答案校准 |
| 查询 Run | `GET /api/v1/agent/runs/{runId}/activity` | 返回持久化的 Run、Step、工具、Token 和耗时 |
| 查询可恢复 Run | `GET /api/v1/agent/runs/recoverable?limit=10` | 返回不超过 `limit` 条记录；默认 10 条、最大 20 条，优先活动 Run，并包含最近 10 分钟内启动的 Run |
| 查询运行快照 | `GET /api/v1/agent/runs/{runId}/runtime-snapshot?afterVersion=0` | 仅在快照版本较新时返回 Redis 中的最新运行态 |
| 取消 Run | `POST /api/v1/agent/runs/{runId}/cancel` | 请求取消仍处于 `RUNNING` 的同步 Agent Run |
| 查询异步任务 | `GET /api/v1/agent/tasks/{taskId}` | 返回任务状态、进度、答案和引用 |
| 订阅异步任务 | `GET /api/v1/agent/tasks/{taskId}/stream` | SSE 发送任务进度及终态答案 |
| 取消异步任务 | `POST /api/v1/agent/tasks/{taskId}/cancel` | 取消 `PENDING/RUNNING` 任务并更新 Turn 与 Run |

消息字段：

| 字段 | 作用 |
| --- | --- |
| `query` | 用户问题，最多 1000 字符 |
| `kbIds` | 本次知识库范围 |
| `assetIdList` | 本次显式文档范围；非空时文档工具不得越界 |
| `limit` | 检索结果数量，范围 1–200 |
| `answerMode` | `STRICT`、`SUMMARY` 或 `EXPLORE` |
| `preferredModalities` | `TEXT`、`IMAGE` 或 `MIXED` |
| `agentEnabled` | 客户端是否请求使用 Agent |

### 2.2 组件职责

- `ConversationServiceImpl` 是稳定的应用服务门面。
- `ConversationMessageUseCase` 负责加载会话、归一化请求范围、生成 `turnId/runId`、调用编排器、在事务中保存 Turn/Task、提交后触发异步任务，以及在 Turn 保存后 Finalize Run。
- `ConversationMessageOrchestrator` 只负责选择 Agent 路径或传统路径。
- `ConversationMessageStreamAdapter` 把同一个消息用例适配为 SSE，不负责业务编排。

### 2.3 范围处理

处理顺序：

1. 加载未软删除的会话；不存在则返回 `CONVERSATION_SESSION_NOT_FOUND`。
2. 请求没有 `kbIds` 且会话已有 `kbScope` 时，继承会话范围。
3. 其他情况下，通过 `ConversationKnowledgeAcl` 把 `kbIds` 收敛为当前可见的活动知识库。
4. `answerMode` 默认 `STRICT`。
5. `preferredModalities` 为空时默认 `MIXED`。
6. `assetIdList` 是本次消息的显式文档范围，并原样记录到 Turn；Agent 还会在服务端把这些 ID 解析为当前有效、可访问的资产。

#### 已知缺口：Session Asset Scope

数据库、领域对象和会话 DTO 已包含 `asset_scope/assetIdList`，但业务流程尚未接通：

- `ConversationSessionUseCase.create` 只保存 `kbScope`，没有把创建请求的 `assetIdList` 写入会话。
- `ConversationMessageUseCase.applyConversationScope` 只继承会话 `kbScope`，不会继承会话 `assetScope`。
- 当前生效范围以消息请求的 `assetIdList` 为准；会话级 Asset Scope 尚不可用。

## 3. Agent 与传统路径

只有以下两个条件同时满足才进入 Agent：

```text
request.agentEnabled == true
运行配置 AGENT.enabled == true
```

`AgentWorkflow` 是编排器的必需依赖。Bean 缺失属于装配错误，不会在运行时静默切换到传统流程。

非 Agent 路径先执行 Intent Router：

- `CHAT`：普通聊天生成，不执行知识检索。
- `OTHER`：返回能力范围澄清。
- `KB_QUERY`：进入传统 RAG Pipeline。

Agent 路径不执行 Intent Router。Agent 出现未预期异常且
`AGENT.fallbackToTraditional=true` 时，编排器重新路由并执行传统流程，执行模式记为 `AGENT_FALLBACK`。
`AgentWorkflowImpl` 会把初始化及运行期间未被状态机消费的异常统一封装为
`AgentWorkflowException`，避免 Repository、配置加载或历史读取异常以裸异常形式逃逸。
需要注意：初始化发生在 `RunStarted` 之前；若初始化失败，Agent Trace 尚未创建，
因此只能由编排器执行传统流程降级，不能把一个不存在的 Agent Run 更新为 `FAILED`。

## 4. Run 上下文与预算

### 4.1 不可变 Run State

`AgentState` 是单次同步 Run 的不可变快照，包含：

- `runId`、`turnId`、`sessionId`、`userId` 和本次 KB/Asset 范围。
- 模型消息列表。
- 当前 Run 的 Evidence Registry，以 `segmentId` 去重。
- 已执行 Tool Call Key，用于拒绝重复调用。
- 各工具执行次数。
- 模型步骤数、工具调用数和 Prompt/Completion Token。
- 连续协议错误数、最终答案校验错误数、Trace 顺序和当前步骤。
- 待执行工具队列、Finalizer 尝试次数和 Finalizer 修复上下文。

消息、证据、已执行工具键、工具次数和待执行工具队列在构造快照时复制并转为只读集合。Evidence 不只复制 Map：`ConversationRetrievalCandidate` 以及内部的 Anchor、Bbox、Explain 和命中来源列表都会做防御性复制；空白 `segmentId` 不进入 Registry。每次状态转换返回新快照，旧快照不会随之后的工具结果、Evidence 注册、外部 Candidate 变更或计数更新而变化。

Phase 变更统一经过 `AgentWorkflowPhase.canTransitionTo` 运行时校验。非法迁移立即抛出异常，不能依赖 Runner 当前的事件顺序来隐式保证状态合法。

`AgentBudget` 只根据调用方提供的 `now` 计算是否耗尽、剩余时间和单次调用上限。状态机不读取系统时间，因此同一个 `State + Event` 会得到相同 Transition。

`AgentRequestContextResolver` 会再次从服务端读取有效资源信息，并把上下文锁定为不可扩大的范围；上下文最多展示 50 个 KB 元数据和 20 个 Asset 元数据。

### 4.2 模型上下文

模型上下文包含：

1. 系统提示和回答模式约束。
2. 最近最多 10 个 Turn。
3. 每个历史问题和回答最多 1200 字符。
4. 历史总字符数最多 12000。
5. 删除历史回答中的旧 `[1]`、`[1-1]` 引用，避免把旧 Run 的编号当成当前证据。
6. 当前服务端资源上下文和用户问题。

### 4.3 默认预算

| 预算 | 默认值 |
| --- | ---: |
| 最大模型步骤 | 12 |
| 最大工具调用 | 8 |
| Run 总时限 | 90 秒 |
| 单次模型时限 | 30 秒，并受 Run 剩余时间约束 |

预算耗尽后的处理：

- 已有证据且至少还剩 500ms：进入 Evidence Finalizer。
- 没有证据或已无时间：返回本地澄清，`AnswerStatus=NO_EVIDENCE`，Run 最终为 `DEGRADED`，原因为 `agent_budget_exhausted`。

### 4.4 同步 Agent 状态循环

`AgentWorkflowImpl` 只运行固定事件循环，不再解析协议、执行工具、登记 Evidence、决定重试或构造业务终态：

```mermaid
flowchart LR
    A["AgentState"] --> B["AgentTransitionEngine.transition"]
    B --> C["AgentTransition"]
    C --> D["先发布 Signals"]
    D --> E{"Terminal?"}
    E -- "是" --> F["返回结果或抛 AgentWorkflowException"]
    E -- "否" --> G["执行一个 Command"]
    G --> H["生成 AgentEvent"]
    H --> B
```

Transition 每次最多产生一个外部 Command。模型一次返回多个 Tool Call 时，状态机把它们保存到 `pendingToolCalls`，只发出第一个 `CallTool`。Runner 在 Command 前后根据 `AgentRunCancellationRegistry` 检查业务取消；状态机调度每个待执行工具时先检查预算，再检查重复调用和 `read_document` 限制。预算已经耗尽时直接进入预算降级或 Finalizer，不再额外产生 duplicate/read-limit Signal、Trace 或原因字符串。

非取消 Terminal 在应用 `nextState` 和发布 Terminal Signal 前，必须通过 Cancellation Registry 原子认领终态。若取消请求先被接受，Runner 丢弃尚未发布的 Completion/Failure Transition，并从原非终态 State 发送 `CancellationRequested`；若 Terminal 先认领成功，Registry 会移除运行句柄，稍后到达的取消返回未命中。这样关闭了“effect 后检查已通过、Terminal 提交前又收到取消”的窗口，Presentation fallback 也不能覆盖已经接受的取消。

#### 阶段一：初始化与规划

```mermaid
flowchart LR
    A["AgentRunInitializer"] --> B["初始 AgentState"]
    B --> C["RunStarted"]
    C --> D["PLANNING"]
    D --> E["CallModel"]
    E --> F["ModelCompleted 或 ModelFailed"]
```

Initializer 冻结运行配置和 deadline，按最近优先消耗历史字符预算，再恢复时间正序；同时清理旧引用编号、解析历史 Citation metadata，并构造服务端 Request Context。
初始化位于 Workflow 的统一 `try/finally` 边界内。初始化失败会封装为
`AgentWorkflowException`；只有成功注册取消句柄后，`finally` 才执行注销，避免失败路径遗留悬挂注册。

#### 阶段二：工具执行与证据登记

```mermaid
flowchart LR
    A["ModelCompleted: ToolCalls"] --> B["pendingToolCalls"]
    B --> C{"预算耗尽?"}
    C -- "是" --> D["预算降级或 Finalizer"]
    C -- "否" --> E{"重复或 Read Limit?"}
    E -- "是" --> F["追加 Guard Tool Message"]
    E -- "否" --> G["CallTool"]
    G --> H["ToolCompleted"]
    H --> I["登记可引用 Evidence"]
    I --> J{"还有 Pending Call?"}
    J -- "是" --> C
    J -- "否" --> K["重新 PLANNING"]
    F --> J
```

duplicate 和 read-limit guard 不增加实际工具调用数。合法工具在发出 `CallTool` 前增加 `toolCallCount`；工具完成 Event 携带压缩后的模型消息、Evidence、Trace 摘要和耗时。

#### 阶段三：验证、Finalizer 与 Presentation

```mermaid
flowchart LR
    A["FinalAnswer"] --> B["VerifyAnswer"]
    B --> C{"验证通过?"}
    C -- "首次失败" --> D["追加修复消息并重新规划"]
    C -- "再次失败" --> E["NO_EVIDENCE Terminal"]
    C -- "通过" --> F["PresentAnswer"]
    G["预算/协议/Read Limit 且有 Evidence"] --> H["CallEvidenceFinalizer"]
    H --> I{"单次结果"}
    I -- "失败且可重试" --> H
    I -- "验证通过" --> F
    I -- "超限" --> J["MODEL_FALLBACK Terminal"]
    F --> K["COMPLETED Terminal"]
```

Finalizer 每个 Command 只调用模型一次；最多两次的规则由 Transition Engine 决定。Presentation 每个 Command 最多执行一次流式模型调用。流式失败或输出校验失败时使用已验证草稿完成，不把 Run 改为失败。
答案校验器异常会先转换为 `AnswerVerificationFailed` Event，再由状态机生成
`FAILED` Terminal；Workflow 最终抛出 `AgentWorkflowException`，由上层决定是否切换传统流程。
Presentation 仅允许通过校验的候选触发 `answer_reset`。候选包含内部
`{{segment:...}}`、原始 `segmentId` 或非法可见引用时，无论生成端口是否产生过 delta，
都不会把该候选通过 SSE 发给客户端；有 provisional delta 时 reset 为已验证草稿，
无 delta 时直接使用草稿完成。

#### Signal 与副作用边界

| 对象 | 职责 |
| --- | --- |
| `AgentEvent` | 携带外部调用结果、完成时间、Usage 和语义决策 |
| `AgentCommand` | 描述一次 Model、Tool、Verify、Finalizer 或 Presentation 调用 |
| `AgentSignal` | 固定 Progress、Trace、Metrics 和操作日志所需的顺序号、attempt、计数、details 和失败 cause |
| `AgentRunObserver` | 持久化 Trace，发送 Progress，记录 Metrics；失败按 best-effort 处理 |
| `AgentTerminal` | 保存回答状态、降级原因、Citation、Deferred Task、Run 状态和异常 cause |

`AgentTransitionEngine` 没有 Spring 注解，也不依赖 Repository、Clock、Metrics 或 Listener。`AgentEffectRunner` 只把 Command 路由到 `AgentModelEffect`、`AgentToolEffect` 和 `AgentCompletionEffect`，所有结果先转成 Event。`AgentActionProtocol` 只做 native/JSON 输出到语义决策的解析，不再持有错误计数或 Metrics。

`AgentSignal.Progress.details` 以及 Trace 的 input/output summary 使用保留 null 值能力的不可变快照，而不是 `Map.copyOf`。因此可观测字段出现 null 时不会在 Transition 内触发 NPE，Observer 也不能反向修改 State。答案校验拒绝使用 `AnswerValidationRejected` Signal；Finalizer 或 Presentation 的 effect 异常使用携带原始 cause 的 `EffectFailure` Signal，Observer 负责记录完整异常栈。

同步状态机不接管 `AgentTaskProcessor`。`summarize_documents` 仍以 `DeferredTask/WAITING_TASK` 结束同步 Run，后续异步处理继续使用原有 Task 状态、Lease、Trace Order 和恢复协议。

## 5. 模型协议

### 5.1 工具调用模式

| 模式 | 行为 |
| --- | --- |
| `NATIVE` | 只接受 OpenAI 兼容的原生 `tools/tool_calls` |
| `JSON` | 不发送原生工具定义，要求模型输出严格动作 JSON |
| `AUTO` | 优先原生工具；原生请求异常且仍有时间时回退 JSON 模式 |

原生模式默认设置：

- `tool_choice=required`，可配置为 `auto`。
- `parallelToolCalls=false`。
- 禁止 Spring AI 自动执行工具，由业务状态机统一执行。
- 温度 `0.2`，最大输出 Token `1500`。

JSON 模式只接受以下两种结构：

```json
{"action":"call_tools","toolCalls":[{"id":"call_1","name":"工具名","arguments":{}}]}
```

```json
{"action":"final","answerType":"CHAT|CLARIFICATION|KNOWLEDGE|NO_EVIDENCE","answer":"最终回答","citedSegmentIds":[]}
```

正常回答通过 `deliver_answer` 结束；严格 JSON `final` 用于不支持原生工具调用的模型。

### 5.2 协议错误

普通 Markdown 或自然语言不属于合法动作。处理规则：

| 连续错误 | Run 内证据 | 处理 |
| --- | --- | --- |
| 第一次 | 任意 | 注入修复提示并重试 |
| 第二次 | 有 | 进入 Evidence Finalizer |
| 第二次 | 无 | 返回本地安全回答 |

合法 Tool Call 会清零连续协议错误。第二次错误且无法用证据收尾时，返回
`AnswerStatus=MODEL_FALLBACK`；Turn 保存后 Run 为 `DEGRADED`，原因以
`agent_protocol_error:` 开头。

## 6. Agent 工具

| 工具 | 用途 | 关键输入 | 行为 | 注册证据 |
| --- | --- | --- | --- | --- |
| `find_documents` | 不确定目标文档 | `query`、`limit<=10` | 返回文档、真实 `assetId` 和匹配片段 | 是 |
| `search_knowledge` | 查询事实、规则、流程或相关内容 | `query`、可选 `assetIds`、`limit<=10`、模态 | Query Rewrite 后检索 | 是 |
| `read_document` | 连续阅读一份已定位文档 | `assetId`、`cursor`、`limit<=20` | 按原始顺序分页读取 | 是 |
| `summarize_documents` | 总结、分析或比较 1–3 份文档 | `assetIds`、`instruction`、`language` | 创建 `DOCUMENT_SUMMARY` 异步任务 | 否，任务自行读取全文证据 |
| `deliver_answer` | 提交最终回答 | 四种 `answerType`、正文、引用 ID | 进入最终答案校验 | 否 |

### 6.1 参数、权限与重复调用

`AgentToolExecutor` 统一处理：

1. 工具注册检查，否则 `UNKNOWN_TOOL`。
2. JSON 参数反序列化和 Jakarta Validation，否则 `INVALID_ARGUMENTS`。
3. 业务异常映射为稳定错误码。
4. 安全异常映射为 `PERMISSION_DENIED`。
5. 未知异常映射为 `TOOL_EXECUTION_FAILED`。

工具错误会作为 Tool Message 返回模型，不会自动结束 Run。

`AgentScopeGuard` 对文档类工具执行服务端范围校验：

1. 优先把输入当作 `assetId`，并在授权 KB 内查找。
2. 不是 ID 时，再按完整文件名或标题精确匹配。
3. 同名多文档返回 `AMBIGUOUS_DOCUMENT`。
4. 未找到返回 `DOCUMENT_NOT_FOUND`。
5. 本次请求有 `assetIdList` 时，越界访问返回 `PERMISSION_DENIED`。

`find_documents.documents[].assetId` 是后续文档工具的输入；`matchedSegmentId` 不是 `assetId`。

Tool Call 去重规则：

- 有 `call.id` 时直接使用 ID。
- 无 ID 时使用 `toolName + arguments`。
- 重复调用返回 `DUPLICATE_TOOL_CALL`，不再次执行。

### 6.2 `read_document` 限制

- 默认和最大页大小均为 20；过小值会提升到 10。
- 单次返回正文总量最多约 20000 字符。
- `nextCursor` 使用 Base64 URL-safe 编码。
- 同一 Run 已有证据后，最多真正执行两次 `read_document`；第三次会返回 `READ_LIMIT_REACHED`，随后直接进入 Evidence Finalizer。
- 需要完整通读时应改用 `summarize_documents`。

## 7. RAG 检索子流程

`search_knowledge` 先做对话感知 Query Rewrite；`find_documents` 结合资产元数据和检索结果定位文档。两者最终通过 `ConversationRetrievalAcl` 进入 Search 上下文。

```mermaid
flowchart LR
    A["search_knowledge 查询"] --> B["Query Rewrite"]
    B --> C["文本 BM25"]
    B --> D["文本向量"]
    B --> E["文档图片向量"]
    C --> F["RRF 融合"]
    D --> F
    E --> F
    F --> G["Active generation 过滤"]
    G --> H["按范围执行多样化与 Rerank"]
    H --> I["按 Asset 聚合"]
```

### 7.1 Query Rewrite

- 使用最近最多 5 个 Turn。
- 历史上下文最多 6000 字符，每个字段最多 1200 字符。
- 当前问题最多 2000 字符。
- 模型必须输出严格 JSON。
- 超时、异常或格式错误时使用原始问题。

### 7.2 三路召回、generation gate 与 Rerank

`RetrievalQueryServiceImpl` 的执行顺序：

1. 通过 `SearchKnowledgeAcl` 解析当前可见 KB，并应用 KB/Asset/类型过滤。
2. 为查询生成一次 Embedding。
3. 依次执行全文/关键词召回、文本向量召回、文档图片向量召回；当前实现没有并行调度这三次调用。
4. 以 `segmentId` 做 RRF 融合。
5. 查询每个资产当前激活的 `indexGeneration`，丢弃 generation 不匹配的候选。
6. 多资产或未限定单一资产时，先按 `assetId + segmentType` 做候选多样化，每组最多保留 3 条。
7. 对有竞争力的窗口执行 Rerank。
8. 显式限定单一资产时，为避免在 Rerank 前丢掉仍可能被恢复的同文档片段，延后到 Rerank 之后再做多样化。
9. 按 Asset 聚合 Top Chunks。

RRF：

```text
RRF(segment) = Σ 1 / (rankConstant + rankIndex + 1)
```

Rerank 默认值：

- 窗口 40，最小 20，最大 80。
- 标准化检索分数权重 `0.6`，Rerank 分数权重 `0.4`。
- Rerank 异常或空结果时保留 RRF 顺序。

新 generation 激活前，查询读取旧 generation。激活后，即使 Elasticsearch 暂时保留新旧 Segment，也只有新 generation 能通过过滤。

## 8. 证据收尾

Evidence Finalizer 使用当前 Run 已注册的证据生成受限答案，不再开放工具调用。

触发条件：

- Agent 预算耗尽且已有证据。
- 连续第二次协议错误且已有证据。
- 已有证据后试图第三次执行 `read_document`。

处理方式：

1. 从当前 Run Evidence Registry 选择最多 12 条具有非空 `segmentId` 的证据；缺少可引用 ID 的候选不计入选择数量。
2. 证据输入最多约 24000 字符。
3. 通过 `ConversationGenerationPort` 生成严格的 `KNOWLEDGE|NO_EVIDENCE` JSON。
4. 在剩余时间允许时最多尝试 2 次，每次仍受 Agent 单次模型时限约束。
5. 成功后继续走与 `deliver_answer` 相同的引用校验。

严格 JSON 是生成协议。JSON 缺少 `answerType`、但正文含合法
`{{segment:...}}` Marker 时，解析器可以推断为 `KNOWLEDGE`，但仍必须提供与 Marker
一致的 `citedSegmentIds` 才能通过 Evidence 校验。非 JSON 文本也会保留正文并尝试推断
类型；由于它没有独立的 `citedSegmentIds` 字段，当前会被校验器以 `INVALID_CITATION`
拒绝，并在预算和次数允许时触发下一次 Finalizer 尝试，不会直接作为合法答案接受。
没有 Marker 且不能确定 Answer Type 的文本同样按无效 Finalizer 输出处理。

Finalizer 不可用或两次调用均失败时：

- 返回 `AnswerStatus=MODEL_FALLBACK`。
- 原因分别为 `agent_evidence_finalization_unavailable` 或 `agent_evidence_finalization_failed`。
- Turn 保存后 Run 终态为 `DEGRADED`。

## 9. 回答与引用

### 9.1 Answer Type

| `answerType` | 规则 |
| --- | --- |
| `CHAT` | 无需证据；禁止引用 ID 和 Segment Marker |
| `CLARIFICATION` | 无需证据；禁止引用 |
| `KNOWLEDGE` | 必须有当前 Run 证据、合法引用 ID 和正文 Marker |
| `NO_EVIDENCE` | 禁止引用；后端按回答模式替换为统一的安全无证据文案 |

`NO_EVIDENCE` 属于业务结果：`AnswerStatus=NO_EVIDENCE`，Turn 保存后 Run 为 `COMPLETED`。

### 9.2 Evidence Registry 与 KNOWLEDGE 校验

Run 内证据按以下结构注册：

```text
segmentId -> ConversationRetrievalCandidate
```

只有本轮 `find_documents`、`search_knowledge`、`read_document` 返回并注册的 Segment 才能引用。历史回答引用、模型编造的 ID 和其他 Run 的证据都无效。

KNOWLEDGE 依次校验当前 Run 是否有证据、引用 ID 是否属于本轮、正文是否包含合法 Marker。任一条件不满足都进入答案修复。

内部 Marker：

```text
结论内容 {{segment:真实SegmentID}}
```

校验与渲染步骤：

1. 模型自行写入 `[数字]` 或 `[数字-数字]` 时，校验器以 `UNTRUSTED_VISIBLE_CITATION` 拒绝该答案；首次失败返回模型修复，不会直接删除后接受。
2. 渲染器只识别当前 Evidence Registry 中的 Marker。
3. 按正文首次出现顺序分配文档和 Segment 索引。
4. 渲染成 `[1-1]`、`[1-2]`、`[2-1]`。
5. 生成包含 `kbId`、`assetId`、`segmentId`、页码、锚点和命中原因的 Citation。

首次校验失败时，错误返回模型修复；再次失败则返回安全的 `NO_EVIDENCE`。后端已生成合法终态答案，因此 Turn 保存后 Run 为 `COMPLETED`。

## 10. 异步文档总结

`summarize_documents` 返回 `AgentDeferredTask`。同步请求先保存 PROCESSING 占位 Turn 和 PENDING Task，事务提交后再触发后台处理。

Task status 与处理阶段是两组独立状态。

Task status：

```text
PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
              |
              +-> PENDING（等待重试）
```

`currentStage`：

```text
QUEUED -> READING -> MAP_SUMMARY -> REDUCE_SUMMARY -> FINALIZING -> COMPLETED
任一执行阶段 -> RETRY_WAIT -> READING（重新执行）
任一活动阶段 -> FAILED | CANCELLED
```

发生可重试失败时，Task status 回到 `PENDING`，`currentStage` 设为 `RETRY_WAIT`；重新 Claim 后，Task status 再次变为 `RUNNING`。成功完成时，Task status 为 `SUCCEEDED`，`currentStage` 为 `COMPLETED`。

### 10.1 默认限制

| 限制 | 默认值 |
| --- | ---: |
| 文档数 | 1–3 |
| 最大 Segment 数 | 500 |
| 最大正文字符 | 500000 |
| Map/Reduce 批次字符 | 12000 |
| 任务总时限 | 10 分钟 |
| 单次总结模型时限 | 90 秒 |
| Lease | 2 分钟 |
| 最大重试 | 2 |
| 轮询间隔 | 5 秒 |

### 10.2 处理阶段

1. `READING`：按文档原始 Segment 顺序读取全文。
2. `MAP_SUMMARY`：按约 12000 字符分批生成局部总结，并保留 Marker。
3. `REDUCE_SUMMARY`：分层合并，直到形成单一草稿。
4. `FINALIZING`：执行一次最终模型压缩，并将安全的可见正文增量发送到任务 SSE。
5. 后端再用 `AgentCitationPolicy.compactMarkers` 确定性收敛引用：最多 10 个不同引用、12 个 Marker、每段最多 3 个 Marker。

任务用 Lease 和 Owner 保证单任务单执行者；Lease 过期的 RUNNING 任务可以重新 Claim。可重试失败的延迟为 `30 秒 × 当前 attempt`，上限 120 秒。

任务 SSE 当前发送：

- `task`：状态、进度和阶段。
- `delta`：仅 Final 阶段的安全可见增量；Map/Reduce 中间结果不会发送。
- `answer_reset`：任务开始、重试或失败时可用空内容清理旧 provisional 输出；成功时在 Task 与 Turn 持久化后发送 canonical 完整答案。
- `citations`：最终 Citation 列表。
- `done`：任务结束。

Final 模型使用不含 Segment ID 的临时引用 token；增量渲染器缓存不完整 token 和 Markdown fence，转换为可见引用后才发送。完整答案经过引用范围校验和确定性密度收敛；若收敛后密度检查仍异常，当前实现记录警告但不单独中止任务。Task 与 Turn 事务保存成功后，再按 `answer_reset → citations → done` 完成。

## 11. 持久化与状态

### 11.1 数据表

| 表 | 作用 |
| --- | --- |
| `conversation_session` | 会话和范围字段；当前只实际写入并继承默认 KB Scope |
| `conversation_turn` | 问题、答案、本次 KB/Asset Scope、引用、执行模式和 Run/Task 关联 |
| `agent_run` | Run 状态、步骤、工具、Token、耗时、降级和错误 |
| `agent_step` | 模型决策、工具结果和异步任务阶段 |
| `agent_task` | 异步总结任务、Lease、进度、重试、答案和错误 |

### 11.2 Run 终态

同步 Agent 先生成结果，再保存 Turn：

```text
RUNNING
  -> AWAITING_TURN
  -> COMPLETED | DEGRADED | FALLBACK
```

具体规则：

- 正常回答、模型声明 `NO_EVIDENCE`、二次答案校验后的安全回答：`COMPLETED`。
- 预算耗尽、协议降级、Evidence Finalizer 不可用或失败：`DEGRADED`。
- Agent 未预期异常后成功改走传统路径：`FALLBACK`。
- Turn 保存失败：`FAILED`，错误码 `turn_persistence_failed`。

异步任务：

```text
RUNNING -> WAITING_TASK -> COMPLETED | FAILED | CANCELLED
```

Run 状态全集为：

```text
RUNNING
AWAITING_TURN
WAITING_TASK
COMPLETED
CANCELLED
FAILED
DEGRADED
FALLBACK
```

API 展示时，`DEGRADED` 映射为 `AGENT_DEGRADED`，`FALLBACK` 映射为 `AGENT_FALLBACK`。

Run 终态写入使用带预期状态的条件更新，而不是无条件覆盖：同步 Workflow 只能从
`RUNNING` 提交结果，Turn 保存后的 Finalizer 只能从 `AWAITING_TURN` 提交终态，
异步 Task 完成、失败或取消只能从 `WAITING_TASK` 提交终态。迟到的完成、失败或取消
不能覆盖已经落库的其他终态。

## 12. SSE 与恢复

### 12.1 消息 SSE

消息 SSE 开启回答流能力。CHAT 直接转发模型增量，传统 RAG 通过增量 JSON 解码器只发送回答正文；需要整体校验的 Agent 引用答案只发送已经验证的文本。最终答案与 provisional 内容不一致时使用 `answer_reset` 校准。

典型事件：

| SSE Event | 典型内容 |
| --- | --- |
| `trace` | `agent_thinking/run_started` |
| `trace` | `agent_thinking/decision_started|decision_completed|decision_failed` |
| `trace` | `tool_call/started` |
| `trace` | `tool_result/completed|failed|duplicate_rejected|read_limit_reached|answer_repair_required` |
| `trace` | `agent_thinking/protocol_retry|protocol_fallback|protocol_finalizing_evidence` |
| `trace` | `agent_thinking/evidence_finalization_started|evidence_finalized|evidence_finalization_failed` |
| `trace` | `task_queued/completed` |
| `delta` | 带 `answerId/revision/sequence` 的安全答案增量 |
| `answer_reset` | canonical 完整答案校准 |
| `citations` | 最终 Citation 列表 |
| `done` | Turn、Run、状态、模式和 Task 元数据 |
| `error` | 业务错误码或内部错误 |

### 12.2 客户端断线

SSE 断开或 120 秒传输超时时：

1. Adapter 只把连接标记为已断开。
2. 后端继续执行消息用例、保存 Turn，并在有 `runId` 时发布最终运行快照。
3. 因连接已断开，不再向该 Emitter 发送答案。
4. 不会因为 SSE 断线自动写入取消标记，也不会把 Run 改成 `CANCELLED`。

取消只由 Run/Task 取消接口或会话删除清理在 `AgentRunCancellationRegistry` 中登记后触发。取消登记会 interrupt 当前执行线程，以便模型、工具等阻塞调用尽快退出；但线程的 interrupt flag 本身不是业务取消依据。框架超时、外部 interrupt 或线程池残留中断若没有对应的 Registry 记录，必须按实际 effect 结果继续或失败，不能伪装成 `CANCELLED/查询已取消`。

取消登记与非取消 Terminal 使用同一 Registry 临界区决定先后顺序：已经接受的取消优先于尚未提交的 `COMPLETED`、`DEGRADED` 或 `FAILED` Terminal；已经原子认领的 Terminal 则不再接受迟到的取消。

同步 Workflow 在所有出口统一清除当前线程的 interrupt flag，包括正常完成、业务取消、`FAILED` Terminal、初始化异常和 Runner 兜底异常，避免线程池复用时把上一轮中断带入下一轮 Run。

### 12.3 状态来源

- MySQL 中的 Conversation、Agent Run、Agent Step 和 Agent Task 是权威数据。
- Redis Runtime Snapshot 是 best-effort 缓存；读写失败不得影响主工作流。
- Snapshot 使用单调递增 `version`，客户端可以用 `afterVersion` 避免重复获取旧快照。
- 默认 TTL 为 35 分钟；实际 TTL 取配置值与“任务总时限 × 最大尝试次数 + 5 分钟”中的较大值。
- 可恢复列表最多返回 20 条，优先活动状态 `RUNNING/WAITING_TASK/AWAITING_TURN`，并包含最近 10 分钟启动的终态 Run。
- Conversation 与 Task 共用的 Answer Event Broker 只覆盖单 JVM。当前系统只支持单 App 实例；Task/Turn 轮询和 Runtime Snapshot 用于客户端断线或进程重启后的恢复，不是多实例部署的一致性方案。

## 13. 失败与降级

| 场景 | 局部处理 | 最终结果 |
| --- | --- | --- |
| Query Rewrite 失败 | 使用原始 Query | 继续检索 |
| Embedding 为空 | 抛业务异常 | 进入上层异常路径 |
| Rerank 失败或空结果 | 保留 RRF 顺序 | 继续回答 |
| 工具参数或已规范化的执行错误 | `AgentToolResult.failure` 返回模型，并保留既有 FAILED Trace/Progress | 模型可修复后重试 |
| Tool Effect 边界异常 | 转为 `ToolFailed` 并保留 cause；不新增 Step、Progress 或 step latency | `FAILED` Terminal，配置允许时走传统路径 |
| 重复工具调用 | `DUPLICATE_TOOL_CALL` | 不重复执行 |
| 第一次无合法 Agent 动作 | 注入协议修复消息 | 重试模型 |
| 第二次无合法动作且已有证据 | Evidence Finalizer | 成功则正常完成，失败则 `MODEL_FALLBACK/DEGRADED` |
| 第二次无合法动作且无证据 | 本地安全回答 | `MODEL_FALLBACK/DEGRADED` |
| 第一次最终答案校验失败 | 错误返回模型 | 修复答案 |
| 再次最终答案校验失败 | 本地安全无证据回答 | `NO_EVIDENCE/COMPLETED` |
| 预算耗尽且已有证据 | Evidence Finalizer | 失败时 `MODEL_FALLBACK/DEGRADED` |
| 预算耗尽且无证据 | 本地澄清 | `NO_EVIDENCE/DEGRADED` |
| Agent 初始化失败 | 封装为 `AgentWorkflowException`；此时尚无 Agent Trace | 配置允许时走传统路径，Turn/响应的执行模式为 `AGENT_FALLBACK`；由于没有 `agent_run` 记录，不存在可更新为 `FALLBACK` 的 Run |
| Answer Verifier 异常 | 转成 `AnswerVerificationFailed`，状态机生成 `FAILED` Terminal | Workflow 抛 `AgentWorkflowException`；配置允许时走传统路径 |
| 已建模的 Model 或 Verify 异常 | Effect 转为失败 Event，状态机生成 `FAILED` Terminal 并保留 cause | Workflow 抛 `AgentWorkflowException`；配置允许时走传统路径 |
| 状态循环本身的未预期异常 | Workflow 统一封装为 `AgentWorkflowException`，不让裸异常逃逸 | 配置允许时走传统路径；已有 Trace 的 Run 在传统答案成功保存后为 `FALLBACK`，Trace 尚未创建时只有 Turn/响应记录 `AGENT_FALLBACK` 执行模式 |
| Finalizer 模型异常 | `FinalizerModelFailed` 携带 cause；预算和次数允许时重试 | 超限后 `MODEL_FALLBACK/DEGRADED` |
| Presentation 异常或输出非法 | 记录失败 cause，丢弃非法候选并使用已验证草稿 | Run 仍为 `COMPLETED`，客户端与落库答案一致 |
| 传统 RAG 无合格证据 | 固定无证据模板 | `NO_EVIDENCE` |
| 传统答案格式错误或模型失败 | 默认返回生成失败文案 | `MODEL_FALLBACK`；只有打开 legacy 配置才拼接证据 |
| 异步总结临时失败 | 延迟重试 | 重试耗尽后 `FAILED` |
| 异步总结永久错误 | 不重试 | Task/Run `FAILED`，Turn 更新为 `MODEL_FALLBACK` |

## 14. 传统 RAG

Agent 关闭或 Agent 异常降级时，`KB_QUERY` 执行：

```text
Query Rewrite
  -> Unified Retrieval
  -> Result Card Mapping
  -> 选择最多 5 个可追溯候选
  -> Citation Mapping
  -> Grounded Answer Generation
  -> 只保留答案实际使用的 Citation
  -> 生成引用原因
```

回答模式：

| 模式 | Grounding 数 | 最少证据字符 | 最低 Top Score | 是否允许推测 |
| --- | ---: | ---: | ---: | --- |
| `STRICT` | 5 | 80 | 0.12 | 否 |
| `SUMMARY` | 3 | 60 | 0.10 | 否 |
| `EXPLORE` | 5 | 40 | 0.08 | 是，且必须明确标注 |

传统答案模型必须输出 `ANSWERED|NO_EVIDENCE` 严格 JSON，并使用 `[1]` 引用输入证据。后端校验引用范围、规范化文档索引，并只保留答案真正使用的 Segment。

运行配置 `CONVERSATION.legacyEvidenceFallbackEnabled` 默认是 `false`。
模型失败或格式不合法时，默认返回 `GENERATION_FAILED`；启用该配置后才使用证据拼接旧式保守答案。

## 15. 可观测性与安全

### 15.1 Trace 与指标

模型步骤记录模型、`finishReason`、消息数、工具计划、Token 和耗时。工具步骤记录工具名、Call ID、状态、错误码、证据数、文档数、Segment 数和分页状态。

Observer 同时恢复运行期操作日志：Run 开始/结束、模型决策完成、工具开始/完成/失败、duplicate 拒绝、`read_document` 限制、协议错误和答案校验拒绝都会留下带 `runId`、step/attempt 及必要摘要的日志。Finalizer 和 Presentation 的异常通过 `EffectFailure` Signal 传递，日志记录 phase、异常消息和完整 cause；状态机本身不直接写日志。Observer、Trace、Metrics 或 Progress 发送失败仍按 best-effort 处理，不改变 State 或 Terminal。

`agent.run.result` 只记录正常产生的非 `FAILED` 业务终态，不为抛出 `AgentWorkflowException` 的 `FAILED` Terminal 增加 `status=FAILED` 序列。关闭传统降级、Recorder 实际提交 `FAILED`，或者 Turn 保存失败由 Finalizer 提交 `FAILED` 时，使用 `agent.run.count{status=FAILED}` 统计；默认开启传统降级时，失败 Workflow 先把已有 Run 转为 `AWAITING_TURN`，传统答案保存后最终统计为 `agent.run.count{status=FALLBACK}`。模型调用失败继续保留原有 `MODEL_DECISION` 失败 Trace 和 `decision_failed` Progress；`AgentToolResult.failure` 继续保留原有工具失败 Trace/Progress。只有 Tool Effect 边界自身抛出的异常直接结束 Run，不额外制造 FAILED Step，因此不会新增 `agent.step.latency{step=FAILED}` 或空 usage 的 `agent.model.tokens` 样本。

异步任务阶段不再使用固定 Trace Order `101–106`。Processor 会先锁定对应 Run，
在事务内以当前最大 `stepOrder + 1` 为新阶段分配顺序；同一个 `taskId + attempt + stage`
更新已有 Step，不同重试 attempt 会产生独立 Step。已有旧数据中的固定 `101–106`
仍可被识别并原位更新。阶段语义为：

```text
READING -> MAP_SUMMARY -> REDUCE_SUMMARY -> FINALIZING
        -> RETRY_WAIT -> COMPLETED|FAILED|CANCELLED
```

主要 Metrics：

- `agent.step.latency`
- `agent.model.tokens`
- `agent.run.count`
- `agent.steps`
- `agent.tool.calls`
- `agent.run.tokens`
- `agent.protocol.error`
- `agent.run.result`
- `agent.workflow.fallback.count`
- `conversation.retrieval.latency`
- `conversation.retrieval.topk`
- `kb.search.rerank.calls`
- `kb.search.rerank.fallback`
- `answer.generate.fallback.count`
- `no_evidence.answer.rate`

Trace 只保存响应形态和摘要，不保存完整模型回答或思维链。

### 15.2 安全与可靠性

1. 用户输入、历史消息、文档正文和工具结果都视为不可信数据。
2. 模型只能调用注册工具，不能扩大 KB/Asset 权限。
3. 文档工具必须通过服务端范围校验，不能相信模型提供的 ID。
4. KNOWLEDGE 只能引用当前 Run 的 Evidence Registry。
5. 后端拒绝模型伪造的数字引用，只把合法 Segment Marker 渲染为可见引用。
6. 异步任务使用 Lease、Owner 和条件更新防止重复认领与重复写入；这是任务并发保护，不代表整个应用支持多实例部署。
7. Agent Run 创建前和 Turn 保存前都会锁定未删除会话，避免会话删除与 Run/回答落库竞态。
8. 异步阶段分配 Trace Order 时锁定 Run；Run 不存在时跳过写入，避免新增孤立 Step。
9. Run 与 Task 的终态更新校验预期源状态，避免并发完成、取消和迟到写入互相覆盖。
10. 删除会话时取消活动 Run/Task，并删除相关 Trace 与 Task 记录。
11. Redis 快照不参与权限或业务终态判断，MySQL 仍是权威来源。

## 16. 关键配置

Agent 的开关、工具模式、预算、超时、异步重试和大部分总结限制使用
Settings 中的 `AGENT` 运行配置。Conversation 的传统证据降级使用
`CONVERSATION.legacyEvidenceFallbackEnabled`。同步 Run 在初始化时读取并冻结配置；
异步 Task 每次被 Claim 并开始一个执行 attempt 时重新读取配置，因此同一 Task 的后续
重试可能使用更新后的配置，但单个 attempt 内不会变化。

文档总结数量目前仍由代码常量固定为 1–3 份。Settings Catalog 虽然包含
`AGENT.summaryMaxDocuments`，但 `AgentRuntimeSettings`、`SummarizeDocumentsTool` 和
`AgentTaskProcessor` 当前都使用固定值 3，因此该配置项尚未实际控制运行行为。

异步任务 Lease 固定为 2 分钟，Claim 轮询间隔固定为 5 秒，不接受外部配置。

## 17. 代码索引

| 组件 | 文件 |
| --- | --- |
| 会话消息 REST | `ConversationController.java` |
| Run REST | `AgentRunController.java` |
| 异步任务 REST | `AgentTaskController.java` |
| 应用服务门面 | `ConversationServiceImpl.java` |
| 消息用例、范围归一化和 Turn 保存 | `ConversationMessageUseCase.java` |
| 消息 SSE Adapter | `ConversationMessageStreamAdapter.java` |
| Agent/传统路径编排 | `ConversationMessageOrchestrator.java` |
| KB 可见范围 ACL | `ConversationKnowledgeAcl.java` |
| Agent 请求上下文解析 | `AgentRequestContextResolver.java` |
| Agent 同步运行器 | `AgentWorkflowImpl.java` |
| 纯状态转换 | `AgentTransitionEngine.java`、`AgentTransition.java` |
| 不可变 Run 状态 | `AgentState.java`、`AgentBudget.java` |
| Event、Command、Signal 与 Terminal | `AgentEvent.java`、`AgentCommand.java`、`AgentSignal.java`、`AgentTerminal.java` |
| Run 初始化 | `AgentRunInitializer.java` |
| Effect 路由与纵向边界 | `AgentEffectRunner.java`、`AgentModelEffect.java`、`AgentToolEffect.java`、`AgentCompletionEffect.java` |
| Progress、Trace 与 Metrics Observer | `AgentRunObserver.java` |
| 模型适配与 Tool Choice | `SpringAiAgentModelAdapter.java` |
| 工具注册与执行 | `AgentToolRegistry.java`、`AgentToolExecutor.java` |
| 五个 Agent 工具 | `application/agent/tool/` |
| Conversation/Search 检索边界 | `ConversationRetrievalAcl.java` |
| 三路检索、generation gate 与 Rerank | `RetrievalQueryServiceImpl.java` |
| 对话 Query Rewrite | `QueryRewriteServiceImpl.java` |
| Evidence Finalizer 与流式 Presentation Effect | `AgentCompletionEffect.java` |
| Agent 引用渲染 | `AgentCitationRenderer.java`、`AgentCitationIndexPlan.java` |
| 异步总结处理 | `AgentTaskProcessor.java` |
| 异步任务 SSE | `AgentTaskStreamService.java` |
| Runtime Snapshot | `AgentRuntimeSnapshotService.java` |
| Run 查询与恢复列表 | `AgentRunActivityService.java` |
| Run Trace 与终态 | `AgentTraceRecorder.java`、`AgentRunFinalizer.java` |
| 传统 RAG | `ConversationMessagePipeline.java`、`AnswerGenerationServiceImpl.java` |
| Conversation 表 | `V6__create_conversation_tables.sql` |
| Agent 表 | `V7__create_agent_tables.sql` |
