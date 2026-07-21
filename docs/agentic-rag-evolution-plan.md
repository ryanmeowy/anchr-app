# Anchr 泛化 Agent V1

## 目标

V1 在不破坏现有对话能力的前提下，增加一个显式开启的通用 Agent 运行时。Agent 不依赖固定的“规划、检索、校验、纠错”业务状态机，而是由模型结合真实多轮消息和工具定义持续决策。

```text
agentEnabled=false
  -> Intent Router -> CHAT / OTHER / 传统 RAG

agentEnabled=true 且服务端 app.agent.enabled=true
  -> Agent Loop
      -> 模型直接回答
      -> 或串行调用只读工具
      -> Tool Result 回填消息
      -> deliver_answer
      -> 或创建异步文档总结任务
```

服务端开关默认关闭；前端偏好默认关闭并保存于浏览器，每次请求显式发送 `agentEnabled`。

## 运行时

通用循环由以下协议组成：

- `AgentModelPort`：接收独立角色消息、工具 Schema 和调用级模型参数。
- `AgentTool<I>`：声明工具名、描述、输入类型和执行函数。
- `AgentToolRegistry`：注册工具并通过 Spring AI `JsonSchemaGenerator` 生成 JSON Schema。
- `AgentToolExecutor`：使用 Jackson 反序列化，并在执行前完成 Jakarta Validation。
- `AgentRunState`：只保存消息、证据注册表、Step/Tool 预算、Token 和协议错误次数。
- `AgentTraceRecorder`：保存 Run/Step 的简要 Trace；Trace 写入失败不影响回答。

默认预算：

- 最多 12 个模型决策 Step。
- 最多 8 次工具调用。
- 同步总时限 90 秒。
- 单次模型调用不超过 30 秒，且受 Run 剩余时间约束。
- 工具按模型返回顺序串行执行。

达到预算时，如果本轮已有证据，会再执行一次禁用工具的最终决策；仍无法结束或无证据时返回本地澄清提示。

## 模型兼容

传统 CHAT、Rewrite 和 RAG 回答继续使用现有 `GenerationClient`。Agent 使用独立 `SpringAiAgentModelAdapter`，支持 OpenAI-compatible 原生 Tool Calling。

`app.agent.tool-call-mode`：

- `NATIVE`：只发送和接受原生工具调用。
- `JSON`：不向接口发送原生 tools，使用严格 JSON Action。
- `AUTO`：发送原生 tools，同时兼容模型返回的 JSON Action。

JSON Action 只接受：

```json
{"action":"call_tools","toolCalls":[{"id":"call_1","name":"search_knowledge","arguments":{}}]}
```

或：

```json
{"action":"final","answer":"最终回答","citedSegmentIds":["segment_1"]}
```

连续两次协议错误会终止 Agent，并按配置回退传统链路。

## 上下文与安全边界

- 最近 10 个历史 Turn 按 `user`、`assistant` 独立消息传入。
- 单字段最多 1,200 字符，历史总量最多 12,000 字符。
- System Prompt 只描述 Agent 能力、工具选择、安全边界和终止协议。
- 用户输入、历史消息、文档正文和 Tool Result 都按不可信数据处理。
- 工具只能使用服务端请求上下文内的 KB/Asset Scope。
- Asset 参数会再次解析并校验；越权参数返回 `PERMISSION_DENIED`。
- Agent 模式不伪造 Intent，`intent` 为空，由 `executionMode` 表达实际执行路径。

## 工具

### `find_documents`

组合文件名/标题匹配与知识内容检索，在授权范围内聚合文档候选和代表 Segment。适用于“该找哪份文档”“哪个 PDF 讲某个主题”。

### `search_knowledge`

封装现有 Query Rewrite、混合召回、RRF、Rerank，返回结构化证据，不生成回答。Rerank 异常保留 RRF 顺序。

### `read_document`

按 `kbId + assetId + chunkOrder + segmentId` 稳定分页读取文档，每次最多 20 个 Segment、20,000 字符，并返回游标。

### `summarize_documents`

为 1～3 份已定位文档创建 `DOCUMENT_SUMMARY` 异步任务。工具本身不在同步请求中读取全文或生成总结。

### `deliver_answer`

提交最终 Markdown 和引用 Segment ID。只允许引用本 Run 证据注册表内的 Segment。首次非法引用会作为 Tool Error 回填模型并允许修复一次；再次失败返回 `NO_EVIDENCE`。

后端将 `{{segment:ID}}` 转换为 `[1]`、`[2]`，并生成现有 `ConversationCitation`。普通聊天允许空引用。

## 异步文档任务

`agent_task` 持久化任务请求、状态、进度、Lease、重试、回答和引用。

```text
PENDING
  -> 条件更新原子 Claim
  -> RUNNING + lease_owner + lease_until
  -> 分页读取文档
  -> 12,000 字符 Map Summary
  -> 递归 Reduce
  -> 校验 Segment 引用
  -> 更新原 Conversation Turn
  -> SUCCEEDED
```

限制：

- 最多 3 份文档。
- 最多读取 500 个 Segment、500,000 字符。
- 最长执行 10 分钟。
- 失败最多重试 2 次。
- 超限返回 `DOCUMENT_TOO_LARGE`，不做静默抽样。

调度器每 5 秒扫描可 Claim 任务，并恢复 Lease 已过期的 `RUNNING` 任务。模型调用在事务外执行；初始 Turn 与 Task 使用短事务保存。任务成功或最终失败均幂等更新同一 Turn。

## REST 与 SSE

请求新增：

```json
{"agentEnabled":true}
```

响应和历史 Turn 新增：

```json
{
  "executionMode":"AGENT",
  "agentRunId":"run_xxx",
  "workflowVersion":"general-agent-v1",
  "agentTask":{
    "taskId":"agt_xxx",
    "type":"DOCUMENT_SUMMARY",
    "status":"RUNNING",
    "progress":35,
    "currentStage":"MAP_SUMMARY"
  }
}
```

接口：

- `GET /api/v1/conversations/capabilities`
- `GET /api/v1/agent/tasks/{taskId}`

新增 SSE Trace Stage：`agent_thinking`、`tool_call`、`tool_result`、`task_queued`。Trace 只暴露工具名、状态、数量、耗时和简要摘要，不返回 Prompt、文档全文或思维链。

## 数据库

V7 创建：

- `agent_run`
- `agent_step`
- `agent_task`

`conversation_turn` 增加 `agent_run_id`、`workflow_version`、`execution_mode`、`agent_task_id`，并允许 Agent Turn 的 Intent 字段为空。传统历史 Turn 继续按 `KB_QUERY + LEGACY` 兼容。

## 前端

- Ask Composer 展示 Agent 开关。
- Capability 关闭或旧后端无该接口时按钮禁用。
- 本地保存 Agent 偏好，每个请求显式携带。
- 显示 Agent Thinking、工具调用和异步任务进度。
- 任务从 2 秒开始轮询，最长间隔 5 秒。
- 刷新页面后根据历史 Turn 的 `agentTask` 恢复轮询。
- 任务完成后替换占位回答和引用。
- 传统 RAG、Markdown 和引用预览逻辑保持不变。

## 配置

```yaml
app:
  agent:
    enabled: false
    workflow-version: general-agent-v1
    tool-call-mode: AUTO
    fallback-to-traditional: true
    max-steps: 12
    max-tool-calls: 8
    total-timeout: 90s
    model-timeout: 30s
    task-timeout: 10m
    task-lease: 2m
    task-max-retries: 2
    task-poll-interval: 5s
```

## V1 边界

V1 只提供知识库内只读工具。除创建内部总结任务和更新对应 Turn 外，不包含外部联网、写操作、MCP、多 Agent、人工审批流，也不声明可靠目录/Heading Path 能力。
