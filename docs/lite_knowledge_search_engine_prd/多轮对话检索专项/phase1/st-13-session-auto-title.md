# ST-13 会话标题自动生成

更新时间：2026-04-30

## 1. 目标

- 在会话首轮提问后，为未命名会话自动生成标题。
- 标题来源遵循：优先 `rewrittenQuery`，其次原始 `query`。

## 2. 规则

1. 仅当会话当前无标题时触发自动命名。
2. 仅首轮触发：会话已有 turn 则不再自动改写标题。
3. 标题最大长度 `128` 字符，超长截断。
4. 已手动设置标题的会话保持不变。

## 3. 实现位置

- `ConversationServiceImpl#createMessage`：
  - 入链路前判断是否需要自动命名。
  - 首轮成功后回写 `session.title` 并持久化。

## 4. 回归验证

- `ConversationServiceImplTest#createMessage_shouldSupportMultiTurnAndPersistTrace`
  - 验证空标题会话在首轮后自动命名。
- `ConversationServiceImplTest#createMessage_shouldKeepExistingTitleWhenAlreadyProvided`
  - 验证手动标题不会被自动命名覆盖。
