# Q4-00 产品化接口与路由基线

状态：Frozen for P0 execution  
更新时间：2026-05-21

## 1. 命名约定

Phase 4 P0 统一使用以下 ID 命名：

| 名称 | 说明 |
|---|---|
| `kbId` | 知识库 ID，路径参数和单值字段使用该名称 |
| `kbIds` | 知识库范围，多值请求字段使用该名称 |
| `assetId` | 文档资产 ID |
| `segmentId` | ES `kb_segment` 证据片段 ID |
| `taskId` | 入库任务 ID |
| `itemId` | 入库任务项 ID |
| `sessionId` | 对话会话 ID |
| `turnId` | 对话轮次 ID |

P0 固定用户上下文：

```text
workspaceId = default
userId = system
```

## 2. 页面路由与后端接口范围

| 页面 | 前端路由 | P0 后端接口 |
|---|---|---|
| 知识库 | `/` 或 `/kbs` | `POST/GET/PATCH/DELETE /api/v1/kbs`、`GET /api/v1/kbs/{kbId}/stats` |
| 文档管理 | `/kbs/[kbId]/documents` | `GET /api/v1/ingestion/capabilities`、`POST/GET /api/v1/kbs/{kbId}/ingestion-tasks`、`GET /api/v1/kbs/{kbId}/documents` |
| 对话问答 | `/conversations` | `POST/GET/PATCH/DELETE /api/conversations`、`POST /api/conversations/{sessionId}/messages` |
| 关键词检索 | `/search` | `POST /api/v1/search/kb` |
| 预览 | `/preview/[segmentId]` | `GET /api/v1/preview/segments/{segmentId}` |
| 首页聚合 | `/` 首屏模块 | `GET /api/v1/home/summary` |

## 3. P0 新增接口清单

### 3.1 KnowledgeBase

```text
POST   /api/v1/kbs
GET    /api/v1/kbs
GET    /api/v1/kbs/{kbId}
PATCH  /api/v1/kbs/{kbId}
DELETE /api/v1/kbs/{kbId}
GET    /api/v1/kbs/{kbId}/stats
```

核心 DTO 字段：

```text
kbId
workspaceId
name
description
status
documentCount
segmentCount
lastIngestedAt
createdAt
updatedAt
```

### 3.2 DocumentAsset

```text
GET    /api/v1/kbs/{kbId}/documents
GET    /api/v1/kbs/{kbId}/documents/{assetId}
```

核心 DTO 字段：

```text
assetId
kbId
fileName
title
fileType
mimeType
sizeBytes
fileHash
parseStatus
indexStatus
segmentCount
embeddingProfile
errorCode
errorMessage
createdAt
updatedAt
```

文档删除、`reparse`、`reembed` 属于 P1，接口命名保留：

```text
DELETE /api/v1/kbs/{kbId}/documents/{assetId}
POST   /api/v1/kbs/{kbId}/documents/{assetId}/reparse
POST   /api/v1/kbs/{kbId}/documents/{assetId}/reembed
```

### 3.3 Ingestion

```text
GET  /api/v1/ingestion/capabilities
POST /api/v1/kbs/{kbId}/ingestion-tasks
GET  /api/v1/kbs/{kbId}/ingestion-tasks
GET  /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}
POST /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed
```

核心 DTO 字段：

```text
taskId
kbId
sourceType
status
totalCount
successCount
failureCount
runningCount
items[]

itemId
assetId
fileName
fileHash
stage
status
progress
dedupeResult
errorCode
errorMessage
```

阶段枚举：

```text
UPLOAD
PARSE
CHUNK
EMBED
INDEX
ASKABLE
```

### 3.4 Search

现有接口保持：

```text
POST /api/v1/search/kb
```

P0 请求字段必须补齐：

```text
query
kbIds
topK
limit
strategy
```

P1 扩展字段保留：

```text
assetTypes
dateRange
hitTypes
cursor
sort
withAnswer
answerMode
```

### 3.5 Conversation

现有接口保持：

```text
POST   /api/conversations
GET    /api/conversations
GET    /api/conversations/{sessionId}
PATCH  /api/conversations/{sessionId}
DELETE /api/conversations/{sessionId}
POST   /api/conversations/{sessionId}/messages
GET    /api/conversations/{sessionId}/messages
```

P0 消息请求字段必须补齐：

```text
query
kbIds
topK
limit
strategy
preferredModalities
debug
```

P1 字段保留：

```text
answerMode
stream
```

### 3.6 Preview

现有接口保持：

```text
GET /api/v1/preview/segments/{segmentId}
```

P1 增强接口保留：

```text
GET  /api/v1/preview/segments/{segmentId}/neighbors
POST /api/v1/preview/segments/{segmentId}/refresh
```

### 3.7 Home

```text
GET /api/v1/home/summary
```

P0 响应字段：

```text
favoriteKbs
recentIngestionTasks
recentQuestions
recentCitations
helpLinks
warnings
```

P0 允许 `recentQuestions` 和 `recentCitations` 返回空数组。

## 4. 旧接口下线边界

Streamlit 已废弃，以下旧文本/图片批任务接口已下线，不再作为兼容验证入口：

```text
POST /api/v1/ingestion/text-assets/batch-tasks
GET  /api/v1/ingestion/text-assets/batch-tasks/{taskId}
POST /api/v1/image/batch-tasks
GET  /api/v1/image/batch-tasks/{taskId}
```

Phase 4 正式前端只调用知识库维度的统一入库接口。

## 5. 错误响应契约

错误响应至少包含：

```json
{
  "code": 404,
  "errorCode": "DOCUMENT_NOT_FOUND",
  "message": "Document not found.",
  "traceId": "trace_xxx",
  "details": {}
}
```

说明：

- `code` 保留现有数字状态码兼容。
- `errorCode` 是前端分支处理使用的稳定业务错误码。
- `traceId` 用于排查。
- `details` 默认返回空对象，后续可承载字段级校验错误。

## 6. 鉴权边界

P0 继续使用管理员 token / 本地单用户：

```text
X-Access-Token
```

Phase 4 新增业务接口默认要求鉴权。`/api/v1/auth/refresh-token` 和 `/api/v1/auth/clean-token` 继续使用 `X-Admin-Secret`。
