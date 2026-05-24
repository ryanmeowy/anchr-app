# Phase4 P0 REST API 验收文档

## 1. 目标

本文用于验收 Phase4 Ask First 最小后端闭环：

```text
认证 -> 创建知识库 -> 查询导入能力 -> 创建导入任务 -> 查询任务/文档
-> 搜索/问答 -> 引用预览 -> 首页聚合 -> 设置/账号/审计降级能力
```

所有需要认证的接口统一使用：

```http
X-Access-Token: {{accessToken}}
Content-Type: application/json
```

## 2. 环境变量

| 变量 | 示例 | 说明 |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | 后端服务地址 |
| `adminSecret` | 本地配置值 | 管理员刷新 token 使用 |
| `accessToken` | 登录或刷新得到 | 普通接口鉴权 |
| `kbId` | `kb_xxx` | 创建知识库后回填 |
| `taskId` | `task_xxx` | 创建导入任务后回填 |
| `assetId` | `doc_xxx` | 文档列表返回 |
| `segmentId` | 搜索引用返回 | 预览入口 |

## 3. 验收顺序

### 3.1 获取 token

管理员 token：

```http
GET /api/v1/auth/refresh-token
X-Admin-Secret: {{adminSecret}}
```

本地账号：

```http
POST /api/v1/account/login

{
  "email": "admin@example.com",
  "password": "change-me"
}
```

验收点：

- 未带 token 访问 `@RequireAuth` 接口返回 401。
- 本地账号返回 token、userId、workspaceId、role。

### 3.2 创建知识库

```http
POST /api/v1/kbs

{
  "name": "Phase4 验收知识库",
  "description": "P0 acceptance workspace"
}
```

验收点：

- 返回 `id/name/status/documentCount/segmentCount`。
- `workspaceId/createdBy/updatedBy` 由后端上下文填充。

### 3.3 查询导入能力

```http
GET /api/v1/ingestion/capabilities
```

验收点：

- 返回支持格式、最大文件大小、批次数量、去重策略、阶段枚举。
- 支持格式至少包含 PDF/TXT/MD/IMAGE/DOCX/XLSX/CSV/HTML/URL/PPTX/ZIP。

### 3.4 创建知识库导入任务

```http
POST /api/v1/kbs/{{kbId}}/ingestion-tasks

{
  "sourceType": "UPLOAD",
  "dedupeStrategy": "SKIP",
  "items": [
    {
      "fileName": "contract.pdf",
      "title": "验收合同",
      "fileType": "PDF",
      "mimeType": "application/pdf",
      "sizeBytes": 204800,
      "objectKey": "uploads/phase4/contract.pdf",
      "fileHash": "sha256_contract_demo"
    }
  ]
}
```

URL 导入：

```http
POST /api/v1/kbs/{{kbId}}/ingestion-tasks

{
  "sourceType": "URL",
  "dedupeStrategy": "SKIP",
  "items": [
    {
      "fileName": "remote-contract.pdf",
      "fileType": "PDF",
      "sourceUrl": "https://example.com/files/contract.pdf"
    }
  ]
}
```

验收点：

- 每个 item 有独立状态和失败原因。
- URL 可以是网页，也可以是文件下载 URL；文件类型由 `fileType/mimeType/URL path/响应头` 共同决定。

### 3.5 查询任务与文档

```http
GET /api/v1/kbs/{{kbId}}/ingestion-tasks/{{taskId}}
GET /api/v1/kbs/{{kbId}}/documents?page=1&size=20
GET /api/v1/kbs/{{kbId}}/stats
```

验收点：

- 任务状态包含 `PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED`。
- 文档列表分页稳定，返回 `parseStatus/indexStatus/errorCode/errorMessage`。
- 失败任务可重试：

```http
POST /api/v1/kbs/{{kbId}}/ingestion-tasks/{{taskId}}/retry-failed
POST /api/v1/kbs/{{kbId}}/ingestion-tasks/{{taskId}}/items/{{itemId}}/retry
```

### 3.6 搜索与生成答案

```http
POST /api/v1/search/kb

{
  "query": "付款期限是什么",
  "kbIds": ["{{kbId}}"],
  "limit": 10,
  "withAnswer": true
}
```

```http
POST /api/v1/search/kb-answer

{
  "query": "付款期限是什么",
  "kbIds": ["{{kbId}}"],
  "limit": 6
}
```

验收点：

- 结果不命中未选知识库。
- 返回引用、assetId、segmentId、snippet、anchor 信息。
- `withAnswer=true` 时能在搜索结果上方返回综合答案；provider 不可用时应有可读错误或降级。

### 3.7 对话问答

```http
POST /api/conversations

{
  "title": "验收问答",
  "kbIds": ["{{kbId}}"]
}
```

```http
POST /api/conversations/{{sessionId}}/messages

{
  "query": "合同约定什么时候付款？",
  "kbIds": ["{{kbId}}"],
  "answerMode": "grounded"
}
```

流式问答：

```http
POST /api/conversations/{{sessionId}}/messages/stream
Accept: text/event-stream
```

验收点：

- 回答包含引用。
- 会话列表、详情、消息列表可查询。
- 删除/重命名接口可用。

### 3.8 引用预览

```http
GET /api/v1/preview/segments/{{segmentId}}
GET /api/v1/preview/segments/{{segmentId}}/neighbors?before=2&after=2
POST /api/v1/preview/segments/{{segmentId}}/refresh
```

验收点：

- PDF 返回页码，文本返回片段，图片返回 bbox 或安全降级。
- `previewUrl` 不持久化、不写日志。
- 预览过期可 refresh。

### 3.9 首页聚合

```http
GET /api/v1/home/summary
```

验收点：

- 返回知识库概览、最近问题、最近引用、最近导入等前端首屏所需信息。
- 空数据时返回空列表，不返回 500。

### 3.10 设置、账号、权限、审计

```http
GET /api/v1/settings/capabilities
GET /api/v1/settings/providers
GET /api/v1/settings/search
PATCH /api/v1/settings/search
POST /api/v1/settings/test-connection
GET /api/v1/settings/preferences
PATCH /api/v1/settings/preferences
PATCH /api/v1/settings/providers/selection
```

```http
GET /api/v1/account/me
POST /api/v1/account/users
GET /api/v1/workspaces
GET /api/v1/workspaces/{{workspaceId}}/members
GET /api/v1/audit-logs?limit=50
POST /api/v1/search/web
```

验收点：

- VIEWER 不能删除、导入、修改设置。
- EDITOR 可导入和删除。
- ADMIN/OWNER 可管理设置和成员。
- Web search 未配置 provider 时返回稳定降级结果。

## 4. 负向用例

| 用例 | 预期 |
|---|---|
| 缺少 `X-Access-Token` | 401 |
| 错误 token | 401 |
| 不存在的 `kbId` | `KNOWLEDGE_BASE_NOT_FOUND` |
| 不存在的 `assetId` | `DOCUMENT_NOT_FOUND` |
| 不存在的 `segmentId` | `SEGMENT_NOT_FOUND` |
| 不支持格式 | item 失败并返回可读原因 |
| VIEWER 删除文档 | 403 |
| URL 指向内网地址 | 400，禁止 SSRF |

## 5. 完成标准

- 上述主链路接口按顺序可执行。
- 失败响应包含稳定 code/message。
- 固定 `kbIds` 搜索不串库。
- 引用能进入预览。
- 首页和设置接口空数据可渲染。
