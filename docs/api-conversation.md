# Conversation API

Base path: `/api/conversations`

所有接口均标记 `@RequireAuth`，调用方需要在请求头携带有效 `X-Access-Token`。

---

## 1. 创建会话

`POST /api/conversations`

### Request

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

**Request body:**

```json
{
  "title": "Docker 部署问题",
  "kbIds": ["kb_001", "kb_002"]
}
```

| 字段 | 类型 | 必填 | 校验 | 说明 |
|------|------|------|------|------|
| `title` | string | 否 | max 128 | 会话标题，不传则自动生成 |
| `kbIds` | string[] | 否 | max 100 | 关联知识库 ID 列表 |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "sess_abc123",
    "userId": "user_001",
    "title": "Docker 部署问题",
    "status": "ACTIVE",
    "lastMessagePreview": "Docker Compose 可以通过以下步骤...",
    "kbScope": ["kb_001", "kb_002"],
    "assetScope": ["asset_001", "asset_002"],
    "createdAt": 1751360000000,
    "updatedAt": 1751360000000,
    "expiresAt": 1753952000000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | string | 会话 ID |
| `userId` | string | 所属用户 ID |
| `title` | string | 会话标题 |
| `status` | string | 会话状态（`ACTIVE` / `ARCHIVED`） |
| `lastMessagePreview` | string \| null | 最后一条消息预览 |
| `kbScope` | string[] | 关联知识库 ID 列表 |
| `assetScope` | string[] | 已废弃的会话级字段；资料范围以消息/turn 的 `assetScope` 为准 |
| `createdAt` | long | 创建时间（毫秒时间戳） |
| `updatedAt` | long | 最后更新时间（毫秒时间戳） |
| `expiresAt` | long | 过期时间（毫秒时间戳） |

---

## 2. 会话列表

`GET /api/conversations`

### Request

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

**Query parameters:**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `limit` | int | 否 | 10 | 每页条数（1-50） |
| `cursor` | string | 否 | - | 分页游标，首次请求不传 |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "items": [
      {
        "sessionId": "sess_abc123",
        "userId": "user_001",
        "title": "Docker 部署问题",
        "status": "ACTIVE",
        "lastMessagePreview": "Docker Compose 可以通过以下步骤...",
        "kbScope": ["kb_001"],
        "assetScope": ["asset_001"],
        "createdAt": 1751360000000,
        "updatedAt": 1751360000000,
        "expiresAt": 1753952000000
      }
    ],
    "nextCursor": "eyJvZmZzZXQiOjEwfQ"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `items` | array | 会话列表（按更新时间倒序） |
| `items[].sessionId` | string | 会话 ID |
| `items[].userId` | string | 所属用户 ID |
| `items[].title` | string | 会话标题 |
| `items[].status` | string | 会话状态 |
| `items[].lastMessagePreview` | string \| null | 最后一条消息预览 |
| `items[].kbScope` | string[] | 关联知识库 ID 列表 |
| `items[].assetScope` | string[] | 限定资产 ID 列表 |
| `items[].createdAt` | long | 创建时间（毫秒时间戳） |
| `items[].updatedAt` | long | 最后更新时间（毫秒时间戳） |
| `items[].expiresAt` | long | 过期时间（毫秒时间戳） |
| `nextCursor` | string \| null | 下一页游标，为 null 时表示已到末尾 |

---

## 3. 获取会话详情

`GET /api/conversations/{sessionId}`

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

### Response

`data` 结构同 [创建会话](#1-创建会话) 的 `ConversationSessionDTO`。

---

## 4. 重命名会话

`PATCH /api/conversations/{sessionId}`

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

**Request body:**

```json
{
  "title": "Docker 与 K8s 部署问题"
}
```

| 字段 | 类型 | 必填 | 校验 | 说明 |
|------|------|------|------|------|
| `title` | string | 是 | not blank, max 128 | 新标题 |

### Response

`data` 结构同 [创建会话](#1-创建会话) 的 `ConversationSessionDTO`。

---

## 5. 删除会话

`DELETE /api/conversations/{sessionId}`

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": null
}
```

---

## 6. 发送消息（非流式）

`POST /api/conversations/{sessionId}/messages`

发送用户问题并获取完整回答，等待 LLM 生成完毕后一次性返回。

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

**Request body:**

```json
{
  "query": "Docker Compose 如何配置网络？",
  "limit": 10,
  "kbIds": ["kb_001"],
  "assetIdList": ["asset_001"],
  "answerMode": "GENERATIVE",
  "preferredModalities": ["TEXT", "IMAGE"],
  "debug": false,
  "stream": false
}
```

| 字段 | 类型 | 必填 | 校验 | 说明 |
|------|------|------|------|------|
| `query` | string | 是 | not blank, max 200 | 用户问题 |
| `limit` | int | 否 | 1-200 | 检索结果数量上限 |
| `kbIds` | string[] | 否 | max 100 | 指定知识库范围，不传则使用会话关联的知识库 |
| `assetIdList` | string[] | 否 | max 100 | 仅限定本轮消息的资产范围；不传或传空数组表示本轮不限定资产。无命中不降级，不扩大范围 |
| `answerMode` | string | 否 | max 32 | 回答模式，如 `GENERATIVE`、`DIRECT` |
| `preferredModalities` | string[] | 否 | max 10 | 偏好模态，如 `TEXT`、`IMAGE` |
| `debug` | boolean | 否 | - | 是否返回调试信息（`retrievalTrace`） |
| `stream` | boolean | 否 | - | 非流式接口忽略此字段 |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "sess_abc123",
    "turnId": "turn_xyz789",
    "rewrittenQuery": "Docker Compose 网络配置方法",
    "answer": "Docker Compose 配置网络主要通过 `networks` 字段...",
    "kbScope": ["kb_001"],
    "assetScope": ["asset_001"],
    "answerMode": "GENERATIVE",
    "retrievalStage": "REMOTE",
    "citations": [
      {
        "fileName": "docker-compose-guide.pdf",
        "pageNo": 42,
        "snippet": "在 docker-compose.yml 中定义 networks...",
        "hitType": "TEXT",
        "assetId": "asset_001",
        "segmentId": "seg_abc123",
        "why": {
          "score": 0.92,
          "hitSources": ["VECTOR", "CONTENT"],
          "matchSummary": "语义匹配 + 内容关键词命中 (score: 0.92)"
        }
      }
    ],
    "resultCards": [
      {
        "assetId": "asset_001",
        "assetType": "PDF",
        "fileName": "docker-compose-guide.pdf",
        "title": "Docker Compose 完整指南",
        "score": 0.92,
        "hitCount": 3,
        "primaryHit": {
          "segmentId": "seg_abc123",
          "snippet": "在 docker-compose.yml 中定义 networks...",
          "score": 0.92,
          "pageNo": 42,
          "anchor": {
            "pageNo": 42,
            "chunkOrder": 5,
            "bbox": null,
            "imageWidth": null,
            "imageHeight": null
          },
          "hitType": "TEXT"
        },
        "additionalHits": [
          {
            "segmentId": "seg_abc124",
            "snippet": "使用 docker network create...",
            "score": 0.78,
            "pageNo": 43,
            "anchor": null,
            "hitType": "TEXT"
          }
        ]
      }
    ],
    "retrievalTrace": {
      "limit": 10,
      "strategyEffective": "HYBRID",
      "rewriteReason": "原始 query 已足够精确",
      "rewriteConfidence": 0.95,
      "rewriteFallback": false,
      "retrievedCount": 25,
      "groupedResultCounts": {
        "TEXT": 18,
        "IMAGE": 7
      },
      "topSegmentIds": ["seg_abc123", "seg_abc124", "seg_abc125"],
      "topHitSources": ["VECTOR", "CONTENT"],
      "answerFallback": false,
      "answerFallbackReason": null
    },
    "suggestedQuestions": [
      "Docker Compose 网络驱动有哪些类型？",
      "如何排查 Compose 网络连通性问题？",
      "多服务间如何共享网络？"
    ],
    "createdAt": 1751360000000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | string | 会话 ID |
| `turnId` | string | 本轮对话 ID |
| `rewrittenQuery` | string \| null | LLM 改写后的检索查询 |
| `answer` | string \| null | LLM 生成的回答 |
| `kbScope` | string[] | 实际使用的知识库范围 |
| `assetScope` | string[] | 实际使用的资产 ID 范围 |
| `answerMode` | string \| null | 回答模式 |
| `retrievalStage` | string \| null | 检索阶段标识 |
| **`citations`** | array | 回答引用的来源列表 |
| `citations[].fileName` | string \| null | 源文件名 |
| `citations[].pageNo` | int \| null | 页码 |
| `citations[].snippet` | string \| null | 引用片段文本 |
| `citations[].hitType` | string \| null | 命中类型（`TEXT` / `OCR`） |
| `citations[].assetId` | string \| null | 资产 ID |
| `citations[].segmentId` | string \| null | Segment ID |
| `citations[].why` | object \| null | 检索匹配原因 |
| `citations[].why.score` | double \| null | 相关度得分 |
| `citations[].why.hitSources` | string[] | 命中来源（`VECTOR` / `CONTENT` / `OCR` / `TAG`） |
| `citations[].why.matchSummary` | string \| null | 匹配摘要 |
| **`resultCards`** | array | 按资产聚合的检索结果卡片（Top 3） |
| `resultCards[].assetId` | string | 资产 ID |
| `resultCards[].assetType` | string \| null | 资产类型 |
| `resultCards[].fileName` | string \| null | 文件名 |
| `resultCards[].title` | string \| null | 标题 |
| `resultCards[].score` | double \| null | 资产级别最高相关度得分 |
| `resultCards[].hitCount` | int \| null | 该资产内命中片段数 |
| `resultCards[].primaryHit` | object \| null | 最佳命中片段 |
| `resultCards[].primaryHit.segmentId` | string | Segment ID |
| `resultCards[].primaryHit.snippet` | string \| null | 片段文本 |
| `resultCards[].primaryHit.score` | double \| null | 相关度得分 |
| `resultCards[].primaryHit.pageNo` | int \| null | 页码 |
| `resultCards[].primaryHit.anchor` | object \| null | 定位锚点 |
| `resultCards[].primaryHit.hitType` | string \| null | 命中类型 |
| `resultCards[].additionalHits` | array | 该资产内其他命中片段 |
| **`retrievalTrace`** | object \| null | 检索调试信息（仅 `debug=true` 时返回） |
| `retrievalTrace.limit` | int \| null | 检索数量上限 |
| `retrievalTrace.strategyEffective` | string \| null | 实际生效的检索策略 |
| `retrievalTrace.rewriteReason` | string \| null | Query 改写原因 |
| `retrievalTrace.rewriteConfidence` | double \| null | 改写置信度 |
| `retrievalTrace.rewriteFallback` | boolean \| null | 是否降级使用原始 query |
| `retrievalTrace.retrievedCount` | int \| null | 实际检索命中数 |
| `retrievalTrace.groupedResultCounts` | map | 按类型分组的命中数 |
| `retrievalTrace.topSegmentIds` | string[] | Top 命中 segment ID 列表 |
| `retrievalTrace.topHitSources` | string[] | Top 命中来源列表 |
| `retrievalTrace.answerFallback` | boolean \| null | 回答是否降级 |
| `retrievalTrace.answerFallbackReason` | string \| null | 回答降级原因 |
| `suggestedQuestions` | string[] | LLM 生成的推荐追问（最多 3 个） |
| `createdAt` | long | 消息创建时间（毫秒时间戳） |

---

## 7. 流式消息

`POST /api/conversations/{sessionId}/messages/stream`

以 SSE（Server-Sent Events）方式流式返回 LLM 回答。请求参数与非流式接口一致。

**Content-Type:** `text/event-stream;charset=UTF-8`

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

**Request body:** 同 [发送消息](#6-发送消息非流式) 的 `ConversationMessageRequestDTO`

### Response (SSE Stream)

SSE 事件流，每个事件以 `data:` 开头，包含 JSON 载荷。事件类型通过 `event:` 行区分：

```
event: trace
data: {"stage":"retrieval","message":"started","answerMode":"GENERATIVE"}

event: delta
data: {"text":"Docker Compose "}

event: delta
data: {"text":"配置网络主要通过 `networks` 字段..."}

event: citations
data: [{"fileName":"docker-guide.pdf","pageNo":42,"snippet":"...","assetId":"asset_001","segmentId":"seg_abc","why":{"score":0.92,"hitSources":["VECTOR","CONTENT"],"matchSummary":"语义匹配 + 内容关键词命中 (score: 0.92)"}}]

event: done
data: {"turnId":"turn_xyz","kbScope":["kb_001"],"assetScope":["asset_001"],"answerMode":"GENERATIVE"}

event: error
data: {"code":"GENERATION_FAILED","message":"生成回答失败"}
```

| 事件类型 | 说明 |
|------|------|
| `trace` | 检索阶段开始，携带 `stage`、`answerMode` 等元信息 |
| `delta` | 回答内容增量，`text` 为本次追加的文本片段（48 字符） |
| `citations` | 引用来源列表（完整数组，非逐个推送） |
| `done` | 回答完成，携带 `turnId`、`kbScope`、`assetScope`、`answerMode` |
| `error` | 发生错误，`code` 为错误码，`message` 为错误描述 |

---

## 8. 获取历史消息

`GET /api/conversations/{sessionId}/messages`

获取会话的对话历史记录。

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

**Query parameters:**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `limit` | int | 否 | 20 | 每页条数（1-100） |
| `beforeTurnId` | string | 否 | - | 游标，传入则返回该 turn 之前的消息 |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "sess_abc123",
    "turns": [
      {
        "turnId": "turn_xyz789",
        "sessionId": "sess_abc123",
        "query": "Docker Compose 如何配置网络？",
        "rewrittenQuery": "Docker Compose 网络配置方法",
        "answer": "Docker Compose 配置网络主要通过 `networks` 字段...",
        "kbScope": ["kb_001"],
        "assetScope": ["asset_001"],
        "answerMode": "GENERATIVE",
        "citations": [
          {
            "fileName": "docker-compose-guide.pdf",
            "pageNo": 42,
            "snippet": "在 docker-compose.yml 中定义 networks...",
            "hitType": "TEXT",
            "assetId": "asset_001",
            "segmentId": "seg_abc123",
            "why": {
              "score": 0.92,
              "hitSources": ["VECTOR", "CONTENT"],
              "matchSummary": "语义匹配 + 内容关键词命中 (score: 0.92)"
            }
          }
        ],
        "resultCards": [],
        "createdAt": 1751360000000
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | string | 会话 ID |
| `turns` | array | 对话轮次列表（按时间正序） |
| `turns[].turnId` | string | 轮次 ID |
| `turns[].sessionId` | string | 会话 ID |
| `turns[].query` | string \| null | 用户原始问题 |
| `turns[].rewrittenQuery` | string \| null | 改写后的检索查询 |
| `turns[].answer` | string \| null | LLM 回答 |
| `turns[].kbScope` | string[] | 使用的知识库范围 |
| `turns[].assetScope` | string[] | 使用的资产 ID 范围 |
| `turns[].answerMode` | string \| null | 回答模式 |
| `turns[].citations` | array | 引用列表（同消息响应） |
| `turns[].resultCards` | array | 结果卡片列表 |
| `turns[].createdAt` | long | 创建时间（毫秒时间戳） |

---

## Error Codes

| 场景 | code | errorCode | message |
|------|------|-----------|---------|
| 缺少或错误的 `X-Access-Token` | `401` | `AUTH_TOKEN_INVALID` | token 无效或已过期 |
| 请求参数校验失败 | `400` | `INVALID_REQUEST` | 具体校验错误信息 |
| 会话不存在 | `404` | `SESSION_NOT_FOUND` | 会话未找到 |
| 会话不属于当前用户 | `403` | `FORBIDDEN` | 无权操作该会话 |
| LLM 生成失败 | `500` | `GENERATION_FAILED` | 回答生成失败 |
| 检索服务异常 | `500` | `RETRIEVAL_FAILED` | 检索服务异常 |
