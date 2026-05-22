# ST-12 推荐追问能力

更新时间：2026-04-30

## 1. 目标

- 在 `POST /api/conversations/{sessionId}/messages` 响应中返回 `suggestedQuestions`。
- 每轮返回 `2~4` 条追问建议。
- 建议内容必须与当轮 citations 相关，不做无证据扩写。

## 2. 实现说明

- 新增服务：`FollowUpQuestionService`
  - 输入：`userQuery`、`rewrittenQuery`、`citations`
  - 输出：`List<String> suggestedQuestions`
- 当前版本采用模板策略，不依赖额外模型调用。
- 生成规则：
  1. 优先基于第一条 citation 的 `fileName` 构造追问。
  2. 若存在 `pageNo`，生成“某页深入问题”追问。
  3. 基于 rewrite token 生成“实体关系”追问。
  4. 若命中类型含 `OCR/CAPTION/IMAGE`，追加图示类追问。
  5. 最终裁剪到 4 条；若低于 2 条则返回空列表。

## 3. 边界约束

- citation 为空时返回空列表。
- 不引入与引用无关的新主题。
- 不修改现有 answer/citation/retrieval 主链路行为。

## 4. 回归验证

- `FollowUpQuestionServiceImplTest`
  - 验证 2~4 条数量约束与证据相关性。
  - 验证无证据返回空列表。
- `ConversationServiceImplTest`
  - 验证 `createMessage` 响应包含 `suggestedQuestions`。
