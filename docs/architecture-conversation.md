# Conversation 模块实现

## 架构概览

用户发送一条消息后，经过四级流水线：

```
POST /api/conversations/{sessionId}/messages
  │
  ▼
ConversationServiceImpl.createMessage()
  │
  ▼
ConversationMessagePipeline.execute()
  │
  ├─ Step 1: QueryRewriteService        → 多轮上下文补全指代
  ├─ Step 2: ConversationRetrievalOrchestrator → 搜索召回
  ├─ Step 3: ConversationResultCardMapper     → 聚合 ResultCard
  └─ Step 4: AnswerGenerationService           → LLM 证据约束回答
```

## Step 1: QueryRewriteService

**作用**：结合最近 5 轮对话历史，把用户当前问题改写为适合知识库检索的独立查询。

**实现**：`QueryRewriteServiceImpl.rewrite(sessionId, latestQuery)`

流程：
1. 从 Redis 读最近 5 个 `ConversationTurn`
2. 拼接 prompt（对话历史 + 当前问题 + JSON schema 约束）
3. 调用 `ConversationRewritePort.generateText(prompt)` → LLM 返回 JSON
4. 解析 JSON → `RewriteResult { rewrittenQuery, rewriteReason, topicEntities, preferredModalities, confidence }`
5. 任何异常/空结果 → fallback 使用原问题

**LLM 调用方式**：传入一个拼接好的完整 prompt 字符串，期望 LLM 返回 ```json {...}``` 格式。不是 OpenAI Chat Completions 的 messages 数组模式。

## Step 2: ConversationRetrievalOrchestrator

**作用**：用改写后的查询调用搜索接口，按模态过滤。

**实现**：`ConversationRetrievalOrchestratorImpl.retrieve()`

流程：
1. 构造 `SearchQueryDTO` → 调用 `UnifiedSearchService.search()`
2. 按 `preferredModalities`（TEXT / IMAGE / MIXED）过滤结果
3. 映射 `SearchResultDTO` → `ConversationRetrievalCandidate`（含 bbox 坐标转换）

**问题**：这里直接构造和消费 search 模块的 REST DTO（`SearchQueryDTO` / `SearchResultDTO`），而非使用应用层接口。

## Step 3: ConversationResultCardMapper

**作用**：将 retrieval 结果按资产聚合为 Top3 卡片。

每张 ResultCard 包含：
- `primaryHit`：该资产下分数最高的命中
- `additionalHits`：同资产其他命中片段

## Step 4: AnswerGenerationService

**作用**：基于搜索结果生成证据约束回答。

**实现**：`AnswerGenerationServiceImpl.generate()`

流程：
1. `pickGroundingSegments()`：从 top candidates 和 citations 中取前 5 个作为证据
2. 证据质量校验：
   - 无可用证据 → fallback："未找到足够内容支持该问题"
   - 证据总字数 < 80 且只有 1 条 → fallback
   - 最高分 < 0.12 且只有 1 条 → fallback
3. `buildPrompt()`：拼接证据文本 + 用户问题 + JSON schema → 完整 prompt
4. `conversationRewritePort.generateText(prompt)` → LLM 返回
5. `parseAnswer()`：从返回文本中提取 JSON 的 `answer` 字段
6. 引用校验：`[1] [2]` 编号不能超出证据范围

**LLM 调用方式**：同 Step 1——拼接完整 prompt，期望 LLM 返回 JSON。不是 messages 数组。

Fallback 策略：
- `no_evidence_*`：返回"未找到足够内容"提示 + 改写建议
- `empty_model_answer` / `invalid_answer_citation` / `model_unavailable`：降级为展示检索片段原文

## FollowUpQuestionService

**作用**：基于当前查询和引用，生成 3 个建议追问问题。

调用 `conversationRewritePort.generateText(prompt)`，同上模式。

## 端口接口

```java
// conversation/domain/port/ConversationRewritePort.java
public interface ConversationRewritePort {
    String generateText(String prompt);
}
```

单一方法，传入 prompt 字符串，返回文本。`ConfigDrivenGenerationAdapter` 实现时内部将 `prompt` 包装为 `[{"role":"user","content":prompt}]` 再通过 `GenerationClient` 调 `/chat/completions`。

## 与其他模块的关系

```
conversation
  ├─ → search.application.UnifiedSearchService   (检索编排)
  ├─ → search.application.KbScopeResolver        (KB 可见范围)
  ├─ → search.interfaces.rest.dto.*              (跨层引用 REST DTO)
  ├─ → kb.application.ActivityEventService       (活动记录)
  ├─ → conversation.domain.port.ConversationRewritePort (LLM 生成)
  └─ → conversation.domain.repository.ConversationRepository (会话持久化 → Redis)
```

## 关键问题

1. **prompt 字符串模式 vs messages 数组模式**：接口 `generateText(String prompt)` 把多轮对话上下文和指令全拼进一个字符串，LLM 收到的只是一个 user role 消息。无法利用 system role、assistant role 等对话结构。

2. **Step 2 跨层引用 REST DTO**：`ConversationRetrievalOrchestratorImpl` 直接构造 `SearchQueryDTO` 和消费 `SearchResultDTO`，而不是通过 `UnifiedSearchService` 的应用层模型。

3. **Rewrite 和 Answer 共享同一个 Port**：两者都调 `generateText()`，但 prompt 构建逻辑（中文指令 + JSON schema 约束）各自维护。如果需要切模型或调参数，两个调用点都得改。
