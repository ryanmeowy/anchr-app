# TextAssetApiController 接口文档

更新时间：2026-06-02  
状态：Current Implementation  
依据：
- `src/main/java/com/anchr/core/ingestion/interfaces/rest/TextAssetApiController.java`
- `src/main/java/com/anchr/core/ingestion/application/impl/TextAssetIngestionServiceImpl.java`
- `src/main/java/com/anchr/core/ingestion/interfaces/rest/dto/TextBatchProcessDTO.java`
- `src/main/java/com/anchr/core/ingestion/interfaces/rest/dto/BatchTaskStatusDTO.java`

## 1. 通用约定

### 1.1 Base Path

```text
/api/v1/ingestion/text-assets
```

说明：该 controller 是旧文本入库兼容接口。Phase 4 正式前端优先使用知识库维度统一入库接口；该接口仍可用于 Streamlit、兼容验证或文本入库专项测试。

### 1.2 认证

所有接口均标记 `@RequireAuth`，需要通过认证拦截器。

请求头：

```http
X-Access-Token: <access-token>
```

### 1.3 通用响应

所有接口返回统一 `Result<T>` 包装。

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
  "code": 404,
  "message": "Text task not found",
  "errorCode": "TEXT_TASK_NOT_FOUND",
  "data": null,
  "timestamp": 1777520000000,
  "traceId": "4c5f7c7b-8a37-4d32-85d2-f3210e1a9d9d",
  "details": {},
  "errorId": "4c5f7c7b-8a37-4d32-85d2-f3210e1a9d9d"
}
```

### 1.4 常见错误

| code | errorCode | 场景 |
|---:|---|---|
| 400 | `INVALID_REQUEST` | 参数校验失败、JSON 不合法、URL scheme/host 不合法 |
| 400 | `TEXT_BATCH_ITEMS_REQUIRED` | 创建任务请求体为空数组或 null |
| 401 | `AUTH_TOKEN_INVALID` / `UNAUTHORIZED` | token 缺失、无效或过期 |
| 404 | `TEXT_TASK_NOT_FOUND` | 文本任务不存在或已过期 |
| 404 | `INGEST_TASK_ITEM_NOT_FOUND` | 重试的 itemId 不存在 |
| 409 | `INGEST_RETRY_ONLY_FAILED` | 单项重试的 item 不是 `FAILED` |
| 409 | `INGEST_TASK_RUNNING` | 任务正在运行或锁被占用 |
| 409 | `INGEST_NO_FAILED_ITEMS` | 全量失败重试时没有 `FAILED` item |
| 500 | `TEXT_ASSET_META_NOT_FOUND` | 任务 item 元数据不存在 |
| 500 | `TEXT_PARSER_UNAVAILABLE` | 没有可用文本解析器 |
| 500 | `TEXT_PARSE_FAILED` | 文本读取或解析失败 |
| 500 | `EMBEDDING_RESULT_EMPTY` | 文本向量生成结果为空 |
| 500 | `INGEST_TASK_PAYLOAD_INVALID` | Redis 任务 payload 反序列化失败 |
| 500 | `INGEST_TASK_PAYLOAD_SERIALIZE_FAILED` | Redis 任务 payload 序列化失败 |

## 2. DTO 总览

### 2.1 TextBatchProcessDTO

创建文本批处理任务时，请求体是 `TextBatchProcessDTO[]`，不是 `{ "items": [...] }` 包装对象。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `key` | string | 条件必填 | OSS object key。上传文件类入库需要传；URL 入库可不传 |
| `fileName` | string | 否 | 原始文件名；为空时后端会用 `sourceUrl` 或 `text-asset-{timestamp}` 兜底 |
| `fileHash` | string | 否 | 文件指纹，通常由前端计算并传入 |
| `title` | string | 否 | 资产标题；为空时使用归一化后的 `fileName` |
| `mimeType` | string | 否 | 浏览器或远端响应提供的 MIME type |
| `sourceUrl` | string | 否 | URL/HTML/PDF 等远端资源地址 |

当前 DTO 没有字段级 `@NotBlank` 校验。服务层会按以下规则判断 item 是否可处理：

1. 如果传了 `sourceUrl`，先允许创建为 `PENDING`，实际处理时校验 URL。
2. 如果没有 `sourceUrl`，则 `fileName` 或 `mimeType` 必须能识别为支持的文本类型，并且 `key` 必须非空。
3. 不满足支持条件的 item 不会阻止整批任务创建，而是作为 `FAILED` item 返回，`errorMessage` 为 `File type not supported`。

### 2.2 支持的文本类型

当前 `TextAssetType` 支持通过扩展名或 MIME type 识别以下类型：

| 类型 | 扩展名 | MIME type |
|---|---|---|
| `PDF` | `pdf` | `application/pdf` |
| `TXT` | `txt` | `text/plain` |
| `MARKDOWN` | `md`, `markdown` | `text/markdown`, `text/x-markdown`, `text/plain` |
| `DOCX` | `docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `XLSX` | `xlsx`, `xls` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/vnd.ms-excel` |
| `CSV` | `csv` | `text/csv`, `application/csv` |
| `HTML` | `html`, `htm` | `text/html`, `application/xhtml+xml` |
| `PPTX` | `pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` |
| `ZIP` | `zip` | `application/zip`, `application/x-zip-compressed` |

### 2.3 BatchTaskStatusDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | string | 批处理任务 ID，UUID 字符串 |
| `status` | string | 任务状态：`PENDING`、`RUNNING`、`SUCCESS`、`PARTIAL_FAILED`、`FAILED` |
| `total` | integer | item 总数 |
| `successCount` | integer | 成功 item 数 |
| `failureCount` | integer | 失败 item 数 |
| `runningCount` | integer | 运行中 item 数 |
| `pendingCount` | integer | 等待处理 item 数 |
| `createdAt` | long | 创建时间，毫秒时间戳 |
| `updatedAt` | long | 更新时间，毫秒时间戳 |
| `completedAt` | long/null | 完成时间，未完成时为 null |
| `items` | ItemStatus[] | item 状态明细 |

### 2.4 ItemStatus

| 字段 | 类型 | 说明 |
|---|---|---|
| `itemId` | string | item ID，同时也是当前文本资产临时 assetId |
| `assetType` | string | 当前固定为 `TEXT` |
| `key` | string | OSS object key |
| `fileName` | string | 文件名 |
| `fileHash` | string | 文件指纹 |
| `status` | string | item 状态：`PENDING`、`RUNNING`、`SUCCESS`、`FAILED` |
| `errorMessage` | string/null | 失败原因 |
| `retryCount` | integer | 重试次数 |
| `updatedAt` | long | item 更新时间，毫秒时间戳 |

## 3. 接口列表

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/ingestion/text-assets/batch-tasks` | 创建文本批处理任务 |
| GET | `/api/v1/ingestion/text-assets/batch-tasks/{taskId}` | 查询任务状态 |
| POST | `/api/v1/ingestion/text-assets/batch-tasks/{taskId}/items/{itemId}/retry` | 重试单个失败 item |
| POST | `/api/v1/ingestion/text-assets/batch-tasks/{taskId}/retry-failed` | 重试任务内全部失败 item |

## 4. 接口详情

### 4.1 创建文本批处理任务

```http
POST /api/v1/ingestion/text-assets/batch-tasks
X-Access-Token: <access-token>
Content-Type: application/json
```

请求体：

```json
[
  {
    "key": "text-assets/contract/payment-terms.pdf",
    "fileName": "合同-付款条款.pdf",
    "fileHash": "md5-text-contract-001",
    "title": "合同付款条款",
    "mimeType": "application/pdf"
  },
  {
    "sourceUrl": "https://example.com/docs/mysql-note.html",
    "fileName": "mysql-note.html",
    "title": "MySQL 运维笔记"
  }
]
```

请求约束：

| 约束 | 说明 |
|---|---|
| 请求体类型 | JSON 数组 |
| item 数量 | 最大 20 |
| 空数组 | 返回 `TEXT_BATCH_ITEMS_REQUIRED` |
| 上传文件 item | 需要 `key`，且 `fileName` 或 `mimeType` 可识别为支持类型 |
| URL item | 需要 `sourceUrl`；实际处理时仅允许 `http` / `https`，且禁止私有、本地、内网、多播地址 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "f875ec4a-f8ec-4f4a-b965-fb57db8cd736",
    "status": "PENDING",
    "total": 2,
    "successCount": 0,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 2,
    "createdAt": 1777520000000,
    "updatedAt": 1777520000000,
    "completedAt": null,
    "items": [
      {
        "itemId": "1000000001",
        "assetType": "TEXT",
        "key": "text-assets/contract/payment-terms.pdf",
        "fileName": "合同-付款条款.pdf",
        "fileHash": "md5-text-contract-001",
        "status": "PENDING",
        "errorMessage": null,
        "retryCount": 0,
        "updatedAt": 1777520000000
      },
      {
        "itemId": "1000000002",
        "assetType": "TEXT",
        "key": null,
        "fileName": "mysql-note.html",
        "fileHash": null,
        "status": "PENDING",
        "errorMessage": null,
        "retryCount": 0,
        "updatedAt": 1777520000000
      }
    ]
  }
}
```

不支持 item 示例：

```json
{
  "itemId": "1000000003",
  "assetType": "TEXT",
  "key": "uploads/demo.exe",
  "fileName": "demo.exe",
  "fileHash": "md5-demo",
  "status": "FAILED",
  "errorMessage": "File type not supported",
  "retryCount": 0,
  "updatedAt": 1777520000000
}
```

处理规则：

1. 服务会为每个 item 生成一个 `itemId`。
2. 支持的 item 会保存文本资产元数据到 Redis，TTL 为 24 小时。
3. 任务状态也保存到 Redis，TTL 为 24 小时。
4. 只要存在 `PENDING` item，就会异步提交到 `ingestionTaskExecutor` 处理。
5. 异步处理流程：加载文件或 URL 内容 -> 选择 parser -> parse -> split chunk -> 生成 embedding -> 写入文本 segment repository -> 标记 item 成功。
6. item 处理失败时，item 进入 `FAILED`，错误信息取业务异常详情或处理异常 message。
7. 创建任务接口返回的是提交瞬间状态；需要轮询状态接口获取最终结果。

### 4.2 查询任务状态

```http
GET /api/v1/ingestion/text-assets/batch-tasks/{taskId}
X-Access-Token: <access-token>
```

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taskId` | string | 是 | 批处理任务 ID，不能为空 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "f875ec4a-f8ec-4f4a-b965-fb57db8cd736",
    "status": "SUCCESS",
    "total": 2,
    "successCount": 2,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 0,
    "createdAt": 1777520000000,
    "updatedAt": 1777520015000,
    "completedAt": 1777520015000,
    "items": [
      {
        "itemId": "1000000001",
        "assetType": "TEXT",
        "key": "text-assets/contract/payment-terms.pdf",
        "fileName": "合同-付款条款.pdf",
        "fileHash": "md5-text-contract-001",
        "status": "SUCCESS",
        "errorMessage": null,
        "retryCount": 0,
        "updatedAt": 1777520014000
      }
    ]
  }
}
```

状态说明：

| task.status | 条件 |
|---|---|
| `PENDING` | 全部 item 都是 `PENDING` |
| `RUNNING` | 存在 `PENDING` 或 `RUNNING`，且任务已开始处理 |
| `SUCCESS` | 全部 item 都是 `SUCCESS` |
| `FAILED` | 全部 item 都是 `FAILED` |
| `PARTIAL_FAILED` | 已完成，且同时存在成功和失败 item |

错误：

| code | errorCode | 场景 |
|---:|---|---|
| 404 | `TEXT_TASK_NOT_FOUND` | 任务不存在或 Redis TTL 已过期 |

### 4.3 重试单个失败 item

```http
POST /api/v1/ingestion/text-assets/batch-tasks/{taskId}/items/{itemId}/retry
X-Access-Token: <access-token>
```

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taskId` | string | 是 | 批处理任务 ID，不能为空 |
| `itemId` | string | 是 | item ID，不能为空 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "f875ec4a-f8ec-4f4a-b965-fb57db8cd736",
    "status": "RUNNING",
    "total": 2,
    "successCount": 1,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 1,
    "createdAt": 1777520000000,
    "updatedAt": 1777520100000,
    "completedAt": null,
    "items": [
      {
        "itemId": "1000000002",
        "assetType": "TEXT",
        "key": "text-assets/manual/mysql-note.md",
        "fileName": "mysql-note.md",
        "fileHash": "md5-text-mysql-001",
        "status": "PENDING",
        "errorMessage": null,
        "retryCount": 1,
        "updatedAt": 1777520100000
      }
    ]
  }
}
```

处理规则：

1. 接口会尝试获取任务锁；获取失败返回 `INGEST_TASK_RUNNING`。
2. 任务不存在返回 `TEXT_TASK_NOT_FOUND`。
3. `itemId` 不存在返回 `INGEST_TASK_ITEM_NOT_FOUND`。
4. 只有 `FAILED` item 可以重试；非 FAILED item 返回 `INGEST_RETRY_ONLY_FAILED`。
5. 重试会将 item 状态改为 `PENDING`，清空 `errorMessage`，`retryCount + 1`，并清空任务 `completedAt`。
6. 保存状态后异步重新执行任务处理。

### 4.4 重试全部失败 item

```http
POST /api/v1/ingestion/text-assets/batch-tasks/{taskId}/retry-failed
X-Access-Token: <access-token>
```

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taskId` | string | 是 | 批处理任务 ID，不能为空 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "f875ec4a-f8ec-4f4a-b965-fb57db8cd736",
    "status": "RUNNING",
    "total": 3,
    "successCount": 1,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 2,
    "createdAt": 1777520000000,
    "updatedAt": 1777520200000,
    "completedAt": null,
    "items": []
  }
}
```

处理规则：

1. 只会将当前状态为 `FAILED` 的 item 改为 `PENDING`。
2. 每个被重试 item 的 `retryCount + 1`。
3. 如果没有任何 `FAILED` item，返回 `INGEST_NO_FAILED_ITEMS`。
4. 保存状态后异步重新执行任务处理。

## 5. cURL 示例

### 5.1 创建任务

```bash
curl -X POST 'http://localhost:8080/api/v1/ingestion/text-assets/batch-tasks' \
  -H 'X-Access-Token: <access-token>' \
  -H 'Content-Type: application/json' \
  -d '[
    {
      "key": "text-assets/contract/payment-terms.pdf",
      "fileName": "合同-付款条款.pdf",
      "fileHash": "md5-text-contract-001",
      "title": "合同付款条款",
      "mimeType": "application/pdf"
    }
  ]'
```

### 5.2 查询任务

```bash
curl 'http://localhost:8080/api/v1/ingestion/text-assets/batch-tasks/f875ec4a-f8ec-4f4a-b965-fb57db8cd736' \
  -H 'X-Access-Token: <access-token>'
```

### 5.3 重试单个 item

```bash
curl -X POST 'http://localhost:8080/api/v1/ingestion/text-assets/batch-tasks/f875ec4a-f8ec-4f4a-b965-fb57db8cd736/items/1000000002/retry' \
  -H 'X-Access-Token: <access-token>'
```

### 5.4 重试全部失败 item

```bash
curl -X POST 'http://localhost:8080/api/v1/ingestion/text-assets/batch-tasks/f875ec4a-f8ec-4f4a-b965-fb57db8cd736/retry-failed' \
  -H 'X-Access-Token: <access-token>'
```

## 6. 前端集成提示

1. 创建任务请求体必须是数组；不要包一层 `items`。
2. 创建任务返回后应轮询 `GET /batch-tasks/{taskId}`，直到 `status` 为 `SUCCESS`、`FAILED` 或 `PARTIAL_FAILED`。
3. 任务和临时文本资产元数据 Redis TTL 为 24 小时；超过 TTL 后状态查询会返回 `TEXT_TASK_NOT_FOUND`。
4. 不支持的文件不会让整批提交失败，而是 item 级 `FAILED`。
5. URL 入库在异步处理阶段才会检查 HTTP 状态、私有地址和实际内容；因此创建成功不代表远端资源一定可入库。
6. 对已经成功或正在运行的 item 调单项重试会返回 409。
