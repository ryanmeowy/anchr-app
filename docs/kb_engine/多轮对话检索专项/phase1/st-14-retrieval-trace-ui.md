# ST-14 retrieval trace UI 展示

更新时间：2026-04-30

## 1. 目标

- 在 Streamlit 对话页展示 retrieval trace 的关键字段。
- 覆盖三类信息：
  1. rewrite reason
  2. topK 摘要
  3. explain 信息

## 2. 实现内容

- `ConversationMessageResponseDTO.retrievalTrace` 扩展字段：
  - `strategyEffective`
  - `rewriteReason` / `rewriteConfidence` / `rewriteFallback`
  - `retrievedCount` / `groupedResultCounts`
  - `topSegmentIds` / `topHitSources`
  - `answerFallback` / `answerFallbackReason`
- `ConversationServiceImpl#createMessage` 填充上述字段。
- Streamlit 对话页新增：
  - `Latest Retrieval Trace` 面板（展示最新一次 `POST /messages` 的 trace）
  - 每轮 assistant 消息下的 compact trace 行
  - `Suggested Questions` 展示（如有）

## 3. 边界说明

- 本任务不新增后端接口，仅扩展响应字段。
- 历史 `GET /messages` 返回若无 trace 字段，UI 保持兼容不报错。
- 保持当前 mock 预览可在无后端时展示 trace 效果。

## 4. 回归验证

- `ConversationServiceImplTest`
  - 校验 `retrievalTrace` 中 rewrite/topK/explain 摘要字段被填充。
- `ConversationApiControllerTest`
  - 校验扩展后的 `retrievalTrace` 字段可序列化返回。
- `py_compile`
  - 校验 `ui_streamlit/pages.py` 语法通过。
