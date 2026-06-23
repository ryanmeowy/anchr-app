# ConversationApiController 接口文档

更新时间：2026-06-04  
状态：Current Implementation  
依据：
- `src/main/java/com/anchr/core/conversation/interfaces/rest/ConversationApiController.java`
- `src/main/java/com/anchr/core/conversation/application/impl/ConversationServiceImpl.java`
- `src/main/java/com/anchr/core/conversation/interfaces/rest/dto/*.java`

## 1. 通用约定

### 1.1 Base Path

```text
/api/conversations
```

### 1.2 认证

所有接口均标记 `@RequireAuth`，需要通过认证拦截器。

请求头：

```http
X-Access-Token: <access-token>
```

当前对话服务内部仍使用固定用户标识：

```text
single_user
```

知识库范围会根据当前 `UserContextHolder` 中的 workspace 可见 ACTIVE 知识库进行解析。

### 1.3 通用响应

除 SSE 流式接口外，所有接口返回统一 `Result<T>` 包装。

当前全局异常处理器也返回 `Result<T>`，接口层通常以 HTTP 200 承载业务错误，调用方应以响应体中的 `code`、`errorCode` 和 `message` 判断业务结果。

成功响应：

```json
{
  "code": 200,
  "message": "Success",
  "errorCode": null,
  "data": {},
  "timestamp": 1777520000000,
  "traceId": null,
  "details": null,
  "errorId": null
}
```

错误响应：

```json
{
  "code": 400,
  "message": "Invalid request parameters.",
  "errorCode": "INVALID_REQUEST",
  "data": null,
  "timestamp": 1777520000000,
  "traceId": "7b4f7b9e-9f6d-4c8f-a0a4-6f7f2e8f5c2d",
  "details": {},
  "errorId": "7b4f7b9e-9f6d-4c8f-a0a4-6f7f2e8f5c2d"
}
```

### 1.4 常见错误

| code | errorCode | 场景 |
|---:|---|---|
| 400 | `INVALID_REQUEST` | 参数校验失败、JSON 不合法、cursor 或 beforeTurnId 无效 |
| 401 | `AUTH_TOKEN_INVALID` / `UNAUTHORIZED` | token 缺失、无效或过期 |
| 404 | `CONVERSATION_SESSION_NOT_FOUND` | 会话不存在或已过期 |
| 500 | `INTERNAL_ERROR` | 服务端未预期异常 |
| 503 | `PROVIDER_UNAVAILABLE` | 下游生成、检索等 provider 不可用 |

说明：上表中的 `code` 为响应体业务码，不等同于 HTTP status。

## 2. DTO 总览

### 2.1 ConversationSessionDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | string | 会话 ID，格式为 `cvs_` + UUID 去横线 |
| `userId` | string | 当前实现固定为 `single_user` |
| `title` | string | 会话标题，可为空；首次消息后可自动生成 |
| `status` | string | 会话状态，当前常见值为 `ACTIVE` |
| `lastMessagePreview` | string | 最近一轮回答或问题预览，仅列表接口返回 |
| `kbScope` | string[] | 会话绑定的可见知识库范围 |
| `createdAt` | long | 创建时间，毫秒时间戳 |
| `updatedAt` | long | 更新时间，毫秒时间戳 |
| `expiresAt` | long | 过期时间，毫秒时间戳；当前 TTL 为 30 天 |

### 2.2 ConversationMessageRequestDTO

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `query` | string | 是 | 非空，最长 200 | 用户原始问题 |
| `topK` | integer | 否 | 1-200 | 检索候选数量 |
| `limit` | integer | 否 | 1-200 | 返回或送入回答链路的结果数量 |
| `strategy` | string | 否 | 最长 32 | 检索策略，如 `KB_RRF_RERANK` |
| `kbIds` | string[] | 否 | 最多 100 个 | 本次消息指定的知识库范围 |
| `answerMode` | string | 否 | 最长 32 | 回答模式；支持 `STRICT`、`SUMMARY`、`EXPLORE`；为空或非法值降级为 `STRICT` |
| `preferredModalities` | string[] | 否 | 最多 10 个 | 偏好模态，如 `TEXT`、`IMAGE`、`MIXED` |
| `debug` | boolean | 否 | - | 调试开关；当前由下游链路消费 |
| `stream` | boolean | 否 | - | 请求侧标记；是否流式由接口路径决定 |

回答模式说明：

| 模式 | 说明 |
|---|---|
| `STRICT` | 默认模式；证据门槛最高，只基于证据回答，证据不足时拒答 |
| `SUMMARY` | 摘要模式；仍只基于证据回答，但输出更短，最多保留 3 条核心证据 |
| `EXPLORE` | 探索模式；证据门槛较低，允许单独标注“可能方向/建议”，但事实性结论仍需引用证据 |

### 2.3 ConversationMessageResponseDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | string | 会话 ID |
| `turnId` | string | 消息轮次 ID，格式为 `turn_` + UUID 去横线 |
| `rewrittenQuery` | string | 改写后的检索 query |
| `answer` | string | 最终回答 |
| `kbScope` | string[] | 本轮实际使用的知识库范围 |
| `answerMode` | string | 本轮回答模式 |
| `retrievalStage` | string | 当前实现成功返回 `ANSWERED` |
| `citations` | CitationDTO[] | 引用列表 |
| `resultCards` | ResultCardDTO[] | 按资产聚合的结果卡片 |
| `retrievalTrace` | RetrievalTraceDTO | 检索和回答链路追踪信息 |
| `suggestedQuestions` | string[] | 追问建议 |
| `createdAt` | long | 本轮创建时间，毫秒时间戳 |

### 2.4 CitationDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileName` | string | 文件名 |
| `pageNo` | integer | 页码 |
| `snippet` | string | 引用片段 |
| `hitType` | string | 命中类型 |
| `assetId` | string | 资产 ID |
| `segmentId` | string | 片段 ID |

### 2.5 ResultCardDTO / ResultHitDTO / PreviewAnchorDTO

`ResultCardDTO`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `assetId` | string | 资产 ID |
| `assetType` | string | 资产类型，如 `PDF`、`IMAGE` |
| `fileName` | string | 文件名 |
| `title` | string | 展示标题 |
| `score` | double | 卡片最高或综合分 |
| `hitCount` | integer | 该资产命中的片段数 |
| `primaryHit` | ResultHitDTO | 主命中片段 |
| `additionalHits` | ResultHitDTO[] | 附加命中片段 |

`ResultHitDTO`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `segmentId` | string | 片段 ID |
| `snippet` | string | 片段摘要 |
| `score` | double | 命中分数 |
| `pageNo` | integer | 页码 |
| `anchor` | PreviewAnchorDTO | 预览定位信息 |
| `hitType` | string | 命中类型 |

`PreviewAnchorDTO`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `pageNo` | integer | 页码 |
| `chunkOrder` | integer | chunk 顺序 |
| `bbox` | object | 图像或页面框选区域 |
| `imageWidth` | integer | 图片宽度 |
| `imageHeight` | integer | 图片高度 |

`bbox` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `x` | integer | 左上角 x |
| `y` | integer | 左上角 y |
| `width` | integer | 宽度 |
| `height` | integer | 高度 |
| `unit` | string | 坐标单位 |

### 2.6 RetrievalTraceDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `topK` | integer | 请求中的 topK |
| `limit` | integer | 请求中的 limit |
| `strategy` | string | 请求策略 |
| `strategyEffective` | string | 实际生效策略 |
| `rewriteReason` | string | query rewrite 原因 |
| `rewriteConfidence` | double | query rewrite 置信度 |
| `rewriteFallback` | boolean | query rewrite 是否降级 |
| `retrievedCount` | integer | 候选结果数 |
| `groupedResultCounts` | object | 分组后的结果数量 |
| `topSegmentIds` | string[] | Top segment ID，最多 5 个 |
| `topHitSources` | string[] | Top 命中来源，最多 6 个 |
| `answerFallback` | boolean | 回答是否降级 |
| `answerFallbackReason` | string | 回答降级原因 |

## 3. 接口列表

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/conversations` | 创建会话 |
| GET | `/api/conversations` | 查询会话列表 |
| GET | `/api/conversations/{sessionId}` | 查询会话详情 |
| PATCH | `/api/conversations/{sessionId}` | 重命名会话 |
| DELETE | `/api/conversations/{sessionId}` | 删除会话 |
| POST | `/api/conversations/{sessionId}/messages` | 发送消息并同步返回回答 |
| POST | `/api/conversations/{sessionId}/messages/stream` | 发送消息并以 SSE 返回 |
| GET | `/api/conversations/{sessionId}/messages` | 查询会话消息 |

## 4. 会话接口

### 4.1 创建会话

```http
POST /api/conversations
X-Access-Token: <access-token>
Content-Type: application/json
```

请求体：

```json
{
  "title": "合同付款条款",
  "kbIds": ["kb_001", "kb_002"]
}
```

字段说明：

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `title` | string | 否 | 最长 128 | 会话标题；为空时允许首次消息后自动生成 |
| `kbIds` | string[] | 否 | 最多 100 个 | 会话默认知识库范围 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "cvs_7e7d5fca7d3b4f8e9d2eebc922c5e73a",
    "userId": "single_user",
    "title": "合同付款条款",
    "status": "ACTIVE",
    "lastMessagePreview": null,
    "kbScope": ["kb_001", "kb_002"],
    "createdAt": 1777520000000,
    "updatedAt": 1777520000000,
    "expiresAt": 1780112000000
  }
}
```

处理规则：

1. `title` 会 trim；空字符串按 `null` 处理。
2. `kbIds` 会被解析为当前 workspace 下可见、ACTIVE 的知识库 ID；不可见或空白 ID 会被过滤。
3. 会话 TTL 当前为 30 天，每次重命名或发送消息会刷新 `updatedAt` 和 `expiresAt`。

### 4.2 查询会话列表

```http
GET /api/conversations?limit=20&cursor=<cursor>
X-Access-Token: <access-token>
```

查询参数：

| 参数 | 类型 | 必填 | 校验/默认值 | 说明 |
|---|---|---|---|---|
| `limit` | integer | 否 | 默认 20，接口校验 1-50 | 返回会话数量 |
| `cursor` | string | 否 | 默认从 0 开始 | 翻页游标 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "items": [
      {
        "sessionId": "cvs_7e7d5fca7d3b4f8e9d2eebc922c5e73a",
        "userId": "single_user",
        "title": "合同付款条款",
        "status": "ACTIVE",
        "lastMessagePreview": "合同约定付款应在验收合格后30日内完成。",
        "kbScope": ["kb_001", "kb_002"],
        "createdAt": 1777520000000,
        "updatedAt": 1777520300000,
        "expiresAt": 1780112300000
      }
    ],
    "nextCursor": "eyJvZmZzZXQiOjIwfQ"
  }
}
```

处理规则：

1. 服务层按 `updatedAt desc` 语义从仓储读取最近会话，返回当前页。
2. `nextCursor` 是 Base64 URL-safe 编码的 `{"offset":n}`；前端只需要透传，不要解析或拼装。
3. `lastMessagePreview` 来自最近一轮消息，优先取 answer，其次取 query，去除多余空白后最长 80 字符。
4. 当前 Controller 对 `limit` 有 1-50 校验；未传时服务默认使用 20。

### 4.3 查询会话详情

```http
GET /api/conversations/{sessionId}
X-Access-Token: <access-token>
```

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | 是 | 会话 ID，不能为空 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "cvs_7e7d5fca7d3b4f8e9d2eebc922c5e73a",
    "userId": "single_user",
    "title": "合同付款条款",
    "status": "ACTIVE",
    "lastMessagePreview": null,
    "kbScope": ["kb_001", "kb_002"],
    "createdAt": 1777520000000,
    "updatedAt": 1777520300000,
    "expiresAt": 1780112300000
  }
}
```

错误：

| code | errorCode | 场景 |
|---:|---|---|
| 404 | `CONVERSATION_SESSION_NOT_FOUND` | 会话不存在或已过期 |

### 4.4 重命名会话

```http
PATCH /api/conversations/{sessionId}
X-Access-Token: <access-token>
Content-Type: application/json
```

请求体：

```json
{
  "title": "新标题"
}
```

字段说明：

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `title` | string | 是 | 非空，最长 128 | 新会话标题 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "cvs_7e7d5fca7d3b4f8e9d2eebc922c5e73a",
    "userId": "single_user",
    "title": "新标题",
    "status": "ACTIVE",
    "lastMessagePreview": null,
    "kbScope": ["kb_001", "kb_002"],
    "createdAt": 1777520000000,
    "updatedAt": 1777520600000,
    "expiresAt": 1780112600000
  }
}
```

处理规则：

1. 标题会 trim 后保存。
2. 重命名会刷新 `updatedAt` 和 `expiresAt`。

### 4.5 删除会话

```http
DELETE /api/conversations/{sessionId}
X-Access-Token: <access-token>
```

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": null
}
```

处理规则：

1. 删除前会先校验会话存在。
2. 删除由仓储层执行，预期同时清理会话和消息历史。

## 5. 消息接口

### 5.1 发送消息

```http
POST /api/conversations/{sessionId}/messages
X-Access-Token: <access-token>
Content-Type: application/json
```

请求体：

```json
{
  "query": "合同约定什么时候付款？",
  "topK": 60,
  "limit": 20,
  "strategy": "KB_RRF_RERANK",
  "kbIds": ["kb_001"],
  "answerMode": "STRICT",
  "preferredModalities": ["TEXT", "IMAGE"],
  "debug": false,
  "stream": false
}
```

字段说明见 `2.2 ConversationMessageRequestDTO`。

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "cvs_7e7d5fca7d3b4f8e9d2eebc922c5e73a",
    "turnId": "turn_52e65d6f7db14256bc9384e40d2e7db2",
    "rewrittenQuery": "合同 付款期限 验收后 付款",
    "answer": "合同约定付款应在验收合格后30日内完成。[1]",
    "kbScope": ["kb_001"],
    "answerMode": "STRICT",
    "retrievalStage": "ANSWERED",
    "citations": [
      {
        "fileName": "合同.pdf",
        "pageNo": 3,
        "snippet": "甲方应在验收合格后30日内完成付款。",
        "hitType": "TEXT_CHUNK",
        "assetId": "asset_001",
        "segmentId": "seg_001"
      }
    ],
    "resultCards": [
      {
        "assetId": "asset_001",
        "assetType": "PDF",
        "fileName": "合同.pdf",
        "title": "合同.pdf",
        "score": 0.91,
        "hitCount": 1,
        "primaryHit": {
          "segmentId": "seg_001",
          "snippet": "甲方应在验收合格后30日内完成付款。",
          "score": 0.91,
          "pageNo": 3,
          "anchor": {
            "pageNo": 3,
            "chunkOrder": 12,
            "bbox": null,
            "imageWidth": null,
            "imageHeight": null
          },
          "hitType": "TEXT_CHUNK"
        },
        "additionalHits": []
      }
    ],
    "retrievalTrace": {
      "topK": 60,
      "limit": 20,
      "strategy": "KB_RRF_RERANK",
      "strategyEffective": "KB_RRF_RERANK",
      "rewriteReason": "rewrite_by_model",
      "rewriteConfidence": 0.86,
      "rewriteFallback": false,
      "retrievedCount": 3,
      "groupedResultCounts": {
        "asset_001": 1
      },
      "topSegmentIds": ["seg_001"],
      "topHitSources": ["TEXT"],
      "answerFallback": false,
      "answerFallbackReason": null
    },
    "suggestedQuestions": [
      "验收合格的定义是什么？"
    ],
    "createdAt": 1777520300000
  }
}
```

处理规则：

1. 发送消息前会校验会话存在。
2. 如果请求没有传 `kbIds`，且会话创建时绑定了 `kbScope`，本轮会沿用会话范围。
3. 如果请求传了 `kbIds`，会按当前 workspace 可见 ACTIVE 知识库重新解析，实际范围通过响应 `kbScope` 返回。
4. `answerMode` 大小写不敏感，响应和 turn 快照统一保存为大写枚举名；为空或非法值降级为 `STRICT`。
5. 若会话标题为空且这是第一轮消息，服务会用 `rewrittenQuery` 或原始 `query` 自动生成标题，最长 128 字符。
6. 本轮消息会持久化 query、rewrittenQuery、answer、kbScope、answerMode、citations、resultCards、retrievalTrace。
7. 历史回放中的 `resultCards` 来自 turn 快照，不触发二次检索。
8. `stream` 字段不会把同步接口变成流式；需要流式时调用 `/messages/stream`。

### 5.2 流式发送消息

```http
POST /api/conversations/{sessionId}/messages/stream
X-Access-Token: <access-token>
Content-Type: application/json
Accept: text/event-stream
```

响应内容类型：

```http
text/event-stream;charset=UTF-8
```

请求体同 `5.1 发送消息`。

事件序列：

```text
event: trace
data: {"stage":"retrieval","message":"started","answerMode":"STRICT"}

event: delta
data: {"text":"合同约定付款应在验收合格后30日内完成。"}

event: citations
data: [{"fileName":"合同.pdf","pageNo":3,"snippet":"甲方应在验收合格后30日内完成付款。","hitType":"TEXT_CHUNK","assetId":"asset_001","segmentId":"seg_001"}]

event: done
data: {"turnId":"turn_52e65d6f7db14256bc9384e40d2e7db2","kbScope":["kb_001"],"answerMode":"STRICT"}
```

错误事件：

```text
event: error
data: {"code":"CONVERSATION_SESSION_NOT_FOUND","message":"Conversation session not found"}
```

处理规则：

1. 当前实现复用同步消息链路：先完整执行检索和回答，再将完整 answer 按 48 字符切分为多个 `delta` 事件。
2. 成功时事件顺序为 `trace` -> `delta` 零到多次 -> `citations` -> `done`。
3. `trace` 和 `done` 事件均返回规范化后的 `answerMode`。
4. `done` 事件返回 `turnId`、本轮 `kbScope` 和规范化后的 `answerMode`。
5. 业务异常会发送 `error` 事件并结束 SSE。
6. SSE 接口直接返回事件流，不使用 `Result<T>` 包装。

### 5.3 查询会话消息

```http
GET /api/conversations/{sessionId}/messages?limit=20&beforeTurnId=<turnId>
X-Access-Token: <access-token>
```

查询参数：

| 参数 | 类型 | 必填 | 校验/默认值 | 说明 |
|---|---|---|---|---|
| `limit` | integer | 否 | 默认 20，接口校验 1-100 | 返回 turn 数量 |
| `beforeTurnId` | string | 否 | - | 查询指定 turn 之前的历史 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "cvs_7e7d5fca7d3b4f8e9d2eebc922c5e73a",
    "turns": [
      {
        "turnId": "turn_52e65d6f7db14256bc9384e40d2e7db2",
        "sessionId": "cvs_7e7d5fca7d3b4f8e9d2eebc922c5e73a",
        "query": "合同约定什么时候付款？",
        "rewrittenQuery": "合同 付款期限 验收后 付款",
        "answer": "合同约定付款应在验收合格后30日内完成。[1]",
        "kbScope": ["kb_001"],
        "answerMode": "STRICT",
        "citations": [
          {
            "fileName": "合同.pdf",
            "pageNo": 3,
            "snippet": "甲方应在验收合格后30日内完成付款。",
            "hitType": "TEXT_CHUNK",
            "assetId": "asset_001",
            "segmentId": "seg_001"
          }
        ],
        "resultCards": [],
        "createdAt": 1777520300000
      }
    ]
  }
}
```

处理规则：

1. 先校验会话存在，再读取最近最多 100 条 turn。
2. 不传 `beforeTurnId` 时返回最近 turn，并按 `createdAt asc` 排序。
3. 传 `beforeTurnId` 时，返回该 turn 创建时间之前的 turn。
4. 如果 `beforeTurnId` 不属于该 session 或不存在，返回 `INVALID_REQUEST`。
5. 当前 Controller 对 `limit` 有 1-100 校验；未传时服务默认使用 20。
6. 当前响应 DTO 不包含 `nextBeforeTurnId`；前端如需继续向前翻页，可取本次返回列表第一条 `turnId` 作为下一次 `beforeTurnId`。
7. `ConversationTurnDTO` 当前不返回 `retrievalTrace` 和 `suggestedQuestions`；这两个字段只在发送消息响应中返回。

## 6. cURL 示例

### 6.1 创建会话

```bash
curl -X POST 'http://localhost:8080/api/conversations' \
  -H 'X-Access-Token: <access-token>' \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "合同付款条款",
    "kbIds": ["kb_001"]
  }'
```

### 6.2 发送消息

```bash
curl -X POST 'http://localhost:8080/api/conversations/cvs_xxx/messages' \
  -H 'X-Access-Token: <access-token>' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "合同约定什么时候付款？",
    "topK": 60,
    "limit": 20,
    "strategy": "KB_RRF_RERANK",
    "answerMode": "STRICT"
  }'
```

### 6.3 流式发送消息

```bash
curl -N -X POST 'http://localhost:8080/api/conversations/cvs_xxx/messages/stream' \
  -H 'X-Access-Token: <access-token>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "query": "合同约定什么时候付款？",
    "kbIds": ["kb_001"],
    "answerMode": "STRICT"
  }'
```

## 7. 前端集成提示

1. 普通 REST 接口统一读取 `Result.data`，并使用 `code/errorCode/message/errorId` 做错误展示和排查。
2. 会话列表分页只透传 `nextCursor`，不要依赖 cursor 的内部 offset 结构。
3. 消息发送后，如果会话初始 title 为空，列表或详情中的 title 可能已经自动更新。
4. 历史消息没有 `retrievalTrace` 和 `suggestedQuestions`；需要调试信息时以发送消息响应为准。
5. SSE 当前不是模型 token 级实时流，而是完整答案生成后分块输出，适合前端先接通事件协议和渐进展示体验。
