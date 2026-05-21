# PRD：对话式检索专项

## 1. 项目名称

Conversational Retrieval for Lite Knowledge Base Search Engine
对话式检索专项

---

## 2. 项目背景

当前系统已具备文本与图片知识内容的基础检索能力，能够通过自然语言完成混合召回、融合排序、结果解释和基础工程化处理。

但当前交互方式仍然以**单轮检索**为主，存在几个明显问题：

1. 用户在真实使用中往往不会一次把问题说完整
2. 追问、指代、省略表达会导致检索 query 不完整
3. 用户真正需要的不是“结果列表”，而是“基于结果的可引用回答”
4. 单轮搜索难以承接复杂主题探索，例如：

    * “mysql 架构是什么”
    * “那 InnoDB 呢”
    * “它和 buffer pool 有什么关系”
    * “有没有图示”

因此，需要新增一层**对话式检索能力**，让系统支持：

* 多轮上下文承接
* 会话级 query rewrite
* 检索增强回答
* 引用与来源回传
* 追问和澄清处理

---

## 3. 项目目标

本专项目标是建设一套**对话式检索能力层**，使用户可以通过多轮对话持续探索知识库内容，并获得**基于检索结果、带引用、可追溯**的回答。

核心目标有 4 个：

### 3.1 支持多轮对话检索

用户的后续问题可以继承前文上下文，不要求每轮都重复完整实体和主题。

### 3.2 支持检索增强回答

系统不只返回搜索结果列表，还可以基于命中文本与图片内容生成一段结构化回答。

### 3.3 支持引用与溯源

所有回答必须基于检索到的内容生成，并返回引用信息：

* 文本：文件名 + 页码 + 片段
* 图片：文件名 + 命中类型 + 摘要

### 3.4 保持检索系统属性

本专项不是做开放域聊天机器人。
回答必须受控于检索结果，不能脱离知识库自由发挥。

---

## 4. 非目标

本专项不做以下内容：

* 开放域闲聊
* 通用 Agent 编排
* 多工具自主规划
* 视频模态对话检索
* 多租户会话隔离
* 在线学习排序
* 自动任务执行型 Agent
* 复杂权限系统

---

## 5. 目标用户

### 5.1 核心用户

个人知识库使用者、技术资料检索用户、开发者/学习者。

### 5.2 典型使用场景

* 围绕某个技术主题持续追问
* 从文档解释切换到图片/架构图检索
* 在多个来源之间对比和总结
* 对一个概念逐步深入理解

---

## 6. 典型用户故事

### 用户故事 1：主题追问

作为用户，我先问“mysql 架构是什么”，再问“那 InnoDB 呢”，系统应理解第二问仍然在 mysql 架构语境下。

### 用户故事 2：图文联合检索

作为用户，我问“有没有主从复制的架构图”，系统不仅应找图，还应结合相关文本说明给出回答。

### 用户故事 3：引用可信回答

作为用户，我希望回答中带出引用来源，方便我判断答案是否可靠，并跳回原文或原图查看。

### 用户故事 4：检索失败可解释

作为用户，如果知识库里没有足够内容，系统应明确说明“未找到足够证据”，而不是编答案。

---

## 7. 产品范围

## 7.1 输入

* 用户自然语言问题
* 支持多轮对话
* 当前阶段仅支持文本输入，不支持语音输入

## 7.2 输出

* 对话式回答
* 引用列表
* 可选附加结果卡片
* 可选推荐追问

## 7.3 支持的知识源

* 文本 chunk
* 图片 OCR block
* 图片 caption / tag

---

## 8. 核心产品能力

本专项新增 6 个核心能力模块。

---

## 8.1 会话管理

### 目标

为多轮检索提供上下文承接能力。

### 功能要求

* 支持创建会话
* 支持按会话保存消息历史
* 支持消息角色区分：

    * user
    * assistant
    * system（内部可选）
* 支持记录每轮的检索参数和检索结果摘要

### 关键要求

* 每轮对话必须可追踪
* 每轮消息必须可回放
* 会话支持查询最近 N 轮上下文

### 最小数据字段

#### ConversationSession

* sessionId
* title
* createdAt
* updatedAt
* status

#### ConversationTurn

* turnId
* sessionId
* role
* userText
* rewrittenQuery
* answerText
* citationsJson
* retrievalTraceJson
* createdAt

---

## 8.2 Query Rewrite

### 目标

把用户自然语言问题改写成更适合检索的查询。

### 要解决的问题

* 指代不明：
  “它和 buffer pool 什么关系”
* 省略上下文：
  “那 InnoDB 呢”
* 口语表达：
  “有没有那个 mysql 主从那种图”
* 混合检索意图：
  “讲解一下并给我图示”

### 功能要求

* 读取最近若干轮上下文
* 输出一个适合检索的 rewritten query
* 必要时提取：

    * topic
    * entity
    * intent
    * modality preference

### 示例

用户历史：

* Q1: mysql 架构是什么
* Q2: 那 InnoDB 呢

改写后：

* `mysql 架构中的 InnoDB 作用 实现`

### 输出结构建议

* originalQuery
* rewrittenQuery
* rewriteReason
* topicEntities
* preferredModalities

---

## 8.3 对话式检索编排

### 目标

把 rewrite 后的 query 送入现有搜索系统，并统一管理召回、融合、重排和解释。

### 功能要求

* 复用现有 search 子域能力
* 支持 text + image 联合召回
* 支持 hybrid retrieval
* 支持应用层融合
* 支持 rerank
* 支持 explain 透传

### 关键原则

* 不新造一套搜索系统
* 复用现有 RRF / rerank / explain / metrics
* 对话层只做“会话理解 + 搜索编排”

### 输入

* rewritten query
* conversation context
* retrieval options

### 输出

* topK candidates
* explain details
* grouped results

---

## 8.4 回答生成

### 目标

把检索结果组织成用户可读回答。

### 功能要求

* 基于 topK grounding segments 生成回答
* 回答必须与检索证据一致
* 回答中不能引入未被检索支持的关键结论
* 支持结构化回答风格，例如：

    * 简要定义
    * 核心要点
    * 图文补充说明
    * 参考来源

### 回答风格建议

优先：

* 准确
* 简洁
* 有引用
* 可继续追问

避免：

* 没证据的延伸
* 长篇泛化解释
* 纯模型发挥

### 回答失败策略

当证据不足时：

* 明确提示未找到足够内容
* 返回最相关片段
* 可建议换个问法或缩小范围

---

## 8.5 引用与溯源

### 目标

保证回答可验证。

### 功能要求

回答必须返回 citations。

### 文本引用格式

* fileName
* pageNo
* snippet
* assetId
* segmentId

### 图片引用格式

* fileName
* hitType（OCR / caption / vector）
* snippet
* assetId
* segmentId

### UI 要求

* 用户可查看每条引用
* 引用可跳转到原文页或原图
* 回答与引用一一关联

---

## 8.6 推荐追问

### 目标

增强会话连续性。

### 功能要求

基于当前回答和检索结果，生成 2 到 4 条推荐追问，例如：

* “InnoDB 和 buffer pool 的关系是什么？”
* “有没有相关架构图？”
* “这个机制在哪一页讲得更详细？”

### 约束

* 推荐追问必须与当前证据相关
* 不得偏离当前主题太远

---

## 9. 关键交互流程

## 9.1 单轮问答流程

1. 用户输入问题
2. 系统创建 turn
3. Query Rewrite
4. 调用现有 search 子域完成检索
5. 获得 topK 候选结果
6. 基于 grounding 结果生成回答
7. 返回 answer + citations + suggested follow-ups

## 9.2 多轮追问流程

1. 用户继续提问
2. 系统读取最近 N 轮上下文
3. Query Rewrite 将当前问题补全为完整检索 query
4. 调用检索
5. 生成新的带引用回答
6. 保存本轮 turn

---

## 10. 功能需求明细

## 10.1 会话接口

### 新增接口

#### 创建会话

`POST /api/conversations`

#### 会话内提问

`POST /api/conversations/{sessionId}/messages`

#### 查询会话详情

`GET /api/conversations/{sessionId}`

#### 查询历史消息

`GET /api/conversations/{sessionId}/messages`

### 返回字段建议

* sessionId
* turnId
* answer
* citations
* retrievalTrace
* suggestedQuestions

---

## 10.2 Query Rewrite 服务

### 输入

* sessionId
* latest user query
* recent turns

### 输出

* rewrittenQuery
* extractedEntities
* preferredModalities
* confidence

### 最小策略

* 第一版支持最近 3 到 5 轮上下文
* 第一版优先采用“最近主题 + 当前问题”合并改写
* 第一版不做复杂子问题拆解

---

## 10.3 检索结果组装

### 要求

对 topK candidates 进行分组与裁剪。

### 分组规则建议

* 文本结果按 asset 聚合
* 图片结果按图片实体聚合
* 同一来源多条命中时优先保留最高分片段

### 返回给回答模块的数据

* normalized snippets
* explain tags
* citation-ready metadata

---

## 10.4 Answer 生成策略

### 第一版建议

* answer generation 只读 top 3 到 top 5 个 grounding segments
* 如果 top results 证据冲突，优先保守表达
* 必须输出“根据当前知识库内容”的受控风格

### 推荐输出结构

* 摘要回答
* 关键点列表
* 图文补充
* 参考来源

---

## 11. 非功能需求

## 11.1 性能

### 目标

* 单轮对话响应目标：3–6 秒
* Query Rewrite 不应显著拉高整体时延
* 对话模式下检索仍需保持可接受的响应速度

## 11.2 可用性

* 会话创建与提问接口稳定
* 当 AI 服务不可用时，系统可退化为“返回检索结果而非生成回答”

## 11.3 可追踪性

每轮必须记录：

* rewrite query
* retrieval trace
* topK result IDs
* answer generation input
* citations

## 11.4 可解释性

系统需要支持排查：

* 为什么这样改写 query
* 为什么召回这些结果
* 为什么最终回答这样组织

---

## 12. 监控指标

本专项需要新增会话层和生成层指标。

### 会话指标

* conversation.created.count
* conversation.active.count
* conversation.turn.count

### Rewrite 指标

* query.rewrite.count
* query.rewrite.latency
* query.rewrite.fallback.count

### 检索编排指标

* conversation.retrieval.latency
* conversation.retrieval.topk
* conversation.retrieval.empty.count

### 回答生成指标

* answer.generate.count
* answer.generate.latency
* answer.generate.fallback.count

### 引用指标

* answer.citation.count
* answer.citation.empty.count

### 质量观察指标

* followup.accept.rate
* no-evidence.answer.rate
* conversation.success.rate

---

## 13. 风险与约束

## 13.1 风险

### 检索不稳会放大对话错误

如果底层召回质量不足，多轮会话会不断继承错误语境。

### Rewrite 过度改写

改写太激进，可能偏离用户原意。

### 生成回答幻觉

即使有 grounding，模型仍可能过度总结。

### 响应时间变长

增加 rewrite 和 answer generation 后，整体时延会明显上升。

---

## 14. MVP 范围建议

### P0

* 会话创建
* 会话内提问
* 最近 3 轮上下文 rewrite
* 基于现有检索链路完成 text + image 检索
* 回答生成
* 引用返回
* 基础指标

### P1

* 推荐追问
* 会话标题自动生成
* retrieval trace 展示
* 无证据回答兜底策略优化

### P2

* 会话内结果重用
* 对话级结果缓存
* 更复杂的 query decomposition
* 多轮主题漂移检测

---

## 15. 验收标准

满足以下条件即可视为专项 MVP 完成：

1. 用户可以创建会话并连续提问
2. 第二轮问题可继承前文上下文进行 rewrite
3. 系统可复用现有 search 底盘完成多轮检索
4. 系统返回回答时附带引用
5. 当知识库内容不足时，系统不会强行编造答案
6. 每轮对话可追踪 rewrite、retrieval 和 citations
7. 会话层新增指标可观测

---

## 16. 开发阶段建议

## Phase A：最小闭环

* 会话模型
* 会话接口
* rewrite
* 检索复用
* answer + citations

## Phase B：可用性增强

* 推荐追问
* fallback 策略
* retrieval trace
* 会话列表 / 历史查看

## Phase C：体验优化

* 上下文裁剪策略
* 对话缓存
* 更稳的 query rewrite
* 更好的答案组织
