# Agent RAG 意图路由评估

## 用户问题

新增 agent rag 功能，期望实现类似智能体效果：

- 模型判断用户输入是否需要执行查询，还是闲聊，或其他输入类型。
- 第一步目标：当用户输入类似 `hi`、`hello`、`你好`、`今天怎么样` 时，系统判断为闲聊，不进行知识库查询，而是使用模型直接回复。
- 前端在 `~/personal/anchr-web`。
- 当前阶段只评估合理性、可行性和实现方向，不写详细方案，不直接编码。

## 结论

这个需求合理，值得做，但第一阶段不建议定义成完整的 Agent RAG。

更准确的第一阶段目标是：在现有会话 RAG 前增加一个输入意图路由层，判断用户输入属于 `KB_QUERY`、`CHAT` 还是 `OTHER`。其中 `CHAT` 跳过知识库检索，直接由模型生成普通回复；`KB_QUERY` 继续走现有 RAG 流程。

## 为什么合理

当前后端会话链路是固定顺序：

```text
query rewrite -> unified search -> citations/result cards -> grounded answer
```

对应入口在：

- `src/main/java/com/anchr/core/conversation/application/impl/ConversationMessagePipeline.java`
- `src/main/java/com/anchr/core/conversation/application/impl/ConversationServiceImpl.java`

这意味着 `hi`、`hello`、`你好` 这类输入目前也会进入检索链路，容易得到“未找到足够内容支持该问题”之类的知识库问答结果。这和用户对聊天入口的预期不一致。

增加意图路由能带来几个直接收益：

- 避免无意义的 embedding、检索、rerank 和证据构造成本。
- 降低简单闲聊的响应延迟。
- 避免闲聊污染最近问题、引用统计、空引用指标。
- 让 Ask 页更像自然的智能体入口，而不只是知识库搜索入口。

## 可行性

可行，而且风险可控。

后端已经具备以下基础：

- 会话 API 和 SSE 流式响应已经存在。
- 模型生成能力已经通过 `ConversationRewritePort` / generation adapter 接入。
- 普通搜索链路里已经有 query intent 的雏形，可作为思路参考。

前端也具备基础：

- `anchr-web` 的 Ask 页已经通过 SSE 处理 `trace`、`delta`、`citations`、`done`、`error`。
- 闲聊路径可以保持同样事件协议：`trace(intent=CHAT)` -> `delta` -> `citations=[]` -> `done`。
- 因此前端第一阶段不需要重做交互，只需要可选地增强 trace 展示。

## 推荐实现方向

推荐新增独立的 `ConversationIntentRouter`，放在会话 pipeline 前面。

第一阶段策略：

1. 先用轻量规则识别明确闲聊，例如 `hi`、`hello`、`你好`、`早上好`、`今天怎么样`。
2. 规则不确定时，再调用模型做结构化分类，输出 `type`、`confidence`、`reason`。
3. `type=CHAT` 时跳过 query rewrite 和 retrieval，调用模型直接生成闲聊回复。
4. `type=KB_QUERY` 时沿用现有 RAG 链路。
5. `type=OTHER` 第一阶段可以先按 `KB_QUERY` 兜底，或者返回一个轻量澄清回复，后续再细分。

同时需要注意：

- 闲聊不应该记录为 `QUESTION_ASKED`，否则会污染 Recent Questions。
- 首轮闲聊不建议自动把会话标题设成 `hi` 或 `你好`。
- 闲聊回答应返回空 citations、空 resultCards。
- response / trace 中最好显式带上 intent，便于前端展示和后续排查。

## 方案对比

### 方案 A：只做规则判断

优点：实现最快、成本最低，能解决明确的 `hi/hello/你好`。

缺点：泛化能力差，对“今天怎么样”“你能干嘛”“随便聊聊”这类表达不稳定。

适合做兜底优化，但不适合作为长期主方案。

### 方案 B：扩展现有 QueryRewriteService

优点：改动较少，可以让 rewrite 结果顺便返回 `inputType` 或 `retrievalRequired`。

缺点：职责混杂。`QueryRewriteService` 当前语义是“把用户问题改写成适合检索的 query”，如果同时承担闲聊判断，会让后续维护变得不清晰。

不推荐作为主方案。

### 方案 C：新增独立 Intent Router

优点：职责清晰，容易扩展，后续可以支持工具调用、澄清问题、多类型输入。

缺点：比纯规则或扩展 rewrite 多一个服务和结果模型。

这是推荐方案。

### 方案 D：前端判断闲聊

优点：前端改动快。

缺点：API 行为不一致，其他客户端仍会触发检索，且规则不可统一治理。

不推荐。

### 方案 E：直接引入完整 Agent 框架

优点：长期能力上限更高。

缺点：当前阶段过重，会引入规划、工具注册、状态管理和更多不可控成本。

不建议第一阶段做。

## 当前建议

第一阶段建议做“后端意图路由 + 闲聊直答 + 最小前端展示增强”。

不要急着引入完整 Agent 框架。当前要解决的是输入是否需要检索的问题，用意图路由可以低风险地解决核心体验问题，并为后续 Agent 化留下清晰扩展点。
