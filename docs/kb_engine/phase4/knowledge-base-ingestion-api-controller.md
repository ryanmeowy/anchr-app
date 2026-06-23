# KnowledgeBaseIngestionApiController 接口文档

更新时间：2026-06-08  
状态：Current Implementation  
依据：
- `src/main/java/com/anchr/core/kb/interfaces/rest/ingestion/KnowledgeBaseIngestionApiController.java`
- `src/main/java/com/anchr/core/kb/application/ingestion/KbIngestionApplicationService.java`
- `src/main/java/com/anchr/core/kb/application/ingestion/impl/KbIngestionApplicationServiceImpl.java`
- `src/main/java/com/anchr/core/kb/application/ingestion/impl/KbIngestionTaskProcessorImpl.java`
- `src/main/java/com/anchr/core/kb/interfaces/rest/dto/ingestion/*.java`

## 1. 通用约定

### 1.1 Base Path

```text
/api/v1/kbs/{kbId}
```

说明：该 controller 是 Phase 4 知识库维度的统一入库主接口。前端导入页应优先使用本接口创建、查询和重试入库任务；旧的 `/api/v1/image` 和 `/api/v1/ingestion/text-assets` 属于历史兼容链路或专项测试链路。

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
  "message": "Ingestion task not found",
  "errorCode": "INGESTION_TASK_NOT_FOUND",
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
| 400 | `INVALID_REQUEST` | 参数校验失败、JSON 不合法、`items` 为空、`fileName`/`fileType` 等必要字段缺失 |
| 401 | `AUTH_TOKEN_INVALID` / `UNAUTHORIZED` | token 缺失、无效或过期 |
| 403 | `FORBIDDEN` | 当前用户没有导入权限 |
| 404 | `KNOWLEDGE_BASE_NOT_FOUND` | `kbId` 对应知识库不存在 |
| 404 | `DOCUMENT_NOT_FOUND` | 维护任务中的 `assetId` 不存在，或任务 item 没有关联有效文档 |
| 404 | `INGESTION_TASK_NOT_FOUND` | 任务不存在 |
| 404 | `INGEST_TASK_ITEM_NOT_FOUND` | 重试的 item 不存在 |
| 409 | `INGEST_RETRY_ONLY_FAILED` | 单项重试的 item 不是 `FAILED` 状态 |
| 409 | `INGEST_NO_FAILED_ITEMS` | 全量失败重试时没有 `FAILED` item |
| 500 | `TEXT_PARSER_UNAVAILABLE` | 文本类资产没有可用解析器 |
| 500 | `TEXT_PARSE_FAILED` | 文本解析失败 |
| 500 | `EMBEDDING_RESULT_EMPTY` | 向量生成结果为空 |
| 500 | `EMBEDDING_FAILED` | 向量生成失败 |
| 500 | `INTERNAL_ERROR` | 未预期的系统错误 |

## 2. DTO 总览

### 2.1 IngestionTaskCreateRequestDTO

创建入库任务时，请求体是对象，不是数组。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `sourceType` | string | 否 | `UPLOAD` | 入库来源类型 |
| `dedupeStrategy` | string | 否 | `SKIP` | 去重策略 |
| `items` | array | 是 | - | 本次任务的导入 item，`@NotEmpty`，最多 50 个 |

`sourceType` 可选值：

| 值 | 说明 |
|---|---|
| `UPLOAD` | 上传文件导入，常规文件入库主流程 |
| `URL` | URL 导入，需要 item 提供 `sourceUrl` |
| `RETRY` | 领域枚举值，当前 controller 创建普通任务时不建议前端传入 |
| `REPARSE` | 重新解析维护任务使用 |
| `REEMBED` | 重新向量化维护任务使用 |

`dedupeStrategy` 可选值：

| 值 | 说明 |
|---|---|
| `SKIP` | 同知识库内相同 `fileHash` 已存在时跳过 |
| `OVERWRITE` | 同知识库内相同 `fileHash` 已存在时创建新 asset；新 asset 成功入库后软删旧 asset 并删除旧 segments |
| `VERSIONED` | 同知识库内相同 `fileHash` 已存在时保留旧 asset，创建新 asset 作为新版本 |

`dedupeStrategy` 不传时默认按 `SKIP` 处理；传非法枚举值时返回统一错误响应 `code=400`，不会静默降级。

### 2.2 IngestionTaskCreateItemDTO

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---:|---|---|
| `fileName` | string | 条件必填 | 最大 512 | 文件名；为空时 URL 导入可使用 `sourceUrl` 兜底 |
| `title` | string | 否 | 最大 512 | 文档标题 |
| `fileType` | string | 是 | `@NotBlank`，最大 32 | 文件类型，服务层会转大写后匹配导入能力 |
| `mimeType` | string | 否 | 最大 128 | MIME type |
| `sizeBytes` | long | 否 | - | 文件大小 |
| `objectKey` | string | 条件必填 | 最大 1024 | OSS object key；上传文件导入通常必填，图片处理必填 |
| `fileHash` | string | 否 | 最大 128 | 文件指纹；三种去重策略均依赖该字段 |
| `sourceUrl` | string | 条件必填 | - | URL 导入必填 |

当前支持类型以 `/api/v1/ingestion/capabilities` 为准。当前静态能力包含：

| fileType | 说明 |
|---|---|
| `PDF` / `TXT` / `MD` | P0 文本类 |
| `IMAGE` | P0 图片类，扩展名包括 png、jpg、jpeg、webp |
| `DOCX` / `XLSX` / `CSV` / `HTML` / `URL` | P1 |
| `PPTX` / `ZIP` | P2 |

### 2.3 IngestionTaskDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | string | 入库任务 ID，格式由 `PrefixedIdGenerator` 生成，前缀为 `task` |
| `kbId` | string | 知识库 ID |
| `sourceType` | string | 来源类型 |
| `status` | string | 任务状态 |
| `totalCount` | integer | item 总数 |
| `successCount` | integer | 成功或跳过 item 数 |
| `failureCount` | integer | 失败 item 数 |
| `runningCount` | integer | 运行中 item 数 |
| `createdAt` | string | 创建时间，`LocalDateTime` |
| `updatedAt` | string | 更新时间，`LocalDateTime` |
| `finishedAt` | string/null | 完成时间 |
| `items` | array | item 明细，类型为 `IngestionTaskItemDTO[]` |

任务状态 `status`：

| 值 | 说明 |
|---|---|
| `PENDING` | 存在待处理 item |
| `RUNNING` | 存在运行中 item |
| `SUCCESS` | 全部 item 成功或跳过 |
| `PARTIAL_SUCCESS` | 部分成功，部分失败 |
| `FAILED` | 全部失败 |

### 2.4 IngestionTaskItemDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `itemId` | string | 任务 item ID，前缀为 `item` |
| `assetId` | string/null | 关联文档 ID，前缀为 `doc` |
| `fileName` | string | 文件名 |
| `fileHash` | string/null | 文件指纹 |
| `sourceUrl` | string/null | URL 来源 |
| `stage` | string | 当前阶段 |
| `status` | string | item 状态 |
| `progress` | integer | 进度百分比，0 到 100 |
| `dedupeStrategy` | string/null | 本 item 使用的去重策略 |
| `dedupeResult` | string/null | 去重结果 |
| `duplicateAssetId` | string/null | 命中的重复 asset ID；无重复时为空 |
| `errorCode` | string/null | 失败错误码 |
| `errorMessage` | string/null | 失败错误信息 |
| `updatedAt` | string | 更新时间 |
| `finishedAt` | string/null | 完成时间 |

item 状态：

| 值 | 说明 |
|---|---|
| `PENDING` | 等待处理 |
| `RUNNING` | 正在处理 |
| `SUCCESS` | 处理成功 |
| `FAILED` | 处理失败 |
| `SKIPPED` | 因去重策略跳过 |

阶段 `stage`：

| 值 | 说明 |
|---|---|
| `UPLOAD` | 上传/待接收文件 |
| `PARSE` | 解析，文本解析或图片 OCR/视觉处理 |
| `CHUNK` | 文本切块 |
| `EMBED` | 向量化 |
| `INDEX` | 写入索引 |
| `ASKABLE` | 可问答 |

去重结果 `dedupeResult`：

| 值 | 说明 |
|---|---|
| `NEW` | 未命中重复，按新文档入库 |
| `SKIPPED` | 命中 `SKIP` 去重并跳过 |
| `OVERWRITTEN` | 命中 `OVERWRITE`，新 asset 成功后替换旧 asset |
| `VERSIONED` | 命中 `VERSIONED`，保留旧 asset 并创建新版本 |

### 2.5 IngestionTaskListDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `items` | array | `IngestionTaskSummaryDTO[]` |
| `nextCursor` | string/null | 当前实现固定返回 null |

### 2.6 IngestionTaskSummaryDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | string | 入库任务 ID |
| `kbId` | string | 知识库 ID |
| `sourceType` | string | 来源类型 |
| `status` | string | 任务状态 |
| `totalCount` | integer | item 总数 |
| `successCount` | integer | 成功或跳过 item 数 |
| `failureCount` | integer | 失败 item 数 |
| `runningCount` | integer | 运行中 item 数 |
| `failureReason` | string/null | 第一个失败 item 的可读原因 |
| `createdAt` | string | 创建时间 |
| `updatedAt` | string | 更新时间 |

### 2.7 DocumentMaintenanceTaskDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | string | 新创建的维护任务 ID |
| `assetId` | string | 被维护的文档 ID |
| `status` | string | 任务状态 |

## 3. 接口列表

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/kbs/{kbId}/ingestion-tasks` | 创建知识库入库任务 |
| GET | `/api/v1/kbs/{kbId}/ingestion-tasks` | 查询入库任务列表 |
| GET | `/api/v1/kbs/{kbId}/ingestion-tasks/{taskId}` | 查询入库任务详情 |
| POST | `/api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed` | 重试任务内全部失败 item |
| POST | `/api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/items/{itemId}/retry` | 重试单个失败 item |
| POST | `/api/v1/kbs/{kbId}/documents/{assetId}/reparse` | 创建文档重新解析任务 |
| POST | `/api/v1/kbs/{kbId}/documents/{assetId}/reembed` | 创建文档重新向量化任务 |

## 4. 接口详情

### 4.1 创建知识库入库任务

```http
POST /api/v1/kbs/{kbId}/ingestion-tasks
X-Access-Token: <access-token>
Content-Type: application/json
```

请求体示例：上传文件导入

```json
{
  "sourceType": "UPLOAD",
  "dedupeStrategy": "SKIP",
  "items": [
    {
      "fileName": "合同-付款条款.pdf",
      "title": "合同付款条款",
      "fileType": "PDF",
      "mimeType": "application/pdf",
      "sizeBytes": 204800,
      "objectKey": "uploads/kb_001/contracts/payment-terms.pdf",
      "fileHash": "md5-text-contract-001"
    },
    {
      "fileName": "设备故障图.png",
      "title": "设备故障图",
      "fileType": "IMAGE",
      "mimeType": "image/png",
      "sizeBytes": 102400,
      "objectKey": "uploads/kb_001/images/error-code.png",
      "fileHash": "md5-image-001"
    }
  ]
}
```

请求体示例：URL 导入

```json
{
  "sourceType": "URL",
  "dedupeStrategy": "VERSIONED",
  "items": [
    {
      "fileName": "mysql-note.html",
      "title": "MySQL 运维笔记",
      "fileType": "URL",
      "mimeType": "text/html",
      "sourceUrl": "https://example.com/docs/mysql-note.html"
    }
  ]
}
```

处理规则：

| 规则 | 说明 |
|---|---|
| 权限 | 调用 `permissionService.requireImport()`，需要导入权限 |
| 知识库存在性 | 先校验 `kbId` 对应知识库存在 |
| item 数量 | 服务层最大 50；DTO 也标记 `@Size(max = 50)` |
| `fileType` | 必填，服务层转为大写后通过 `IngestionCapabilityService` 判断是否支持 |
| `fileName` | 为空时可使用 `sourceUrl` 兜底；两者都为空会返回 `INVALID_REQUEST` |
| URL 导入 | `sourceType=URL` 时必须提供 `sourceUrl` |
| 上传导入 | 常规文件应提供 `objectKey`；图片处理阶段会强制要求 `objectKey` 非空 |
| 去重策略 | 有 `fileHash` 时，`SKIP`、`OVERWRITE`、`VERSIONED` 均按同知识库 active asset 查重；无 `fileHash` 时不查重，`dedupeResult=NEW` |
| 去重结果 | `dedupeResult` 表示实际结果；未命中重复时即使选择 `OVERWRITE` 或 `VERSIONED` 也返回 `NEW` |
| 不支持类型 | 不阻止整批任务创建，该 item 会以 `FAILED` 返回，`errorCode=UNSUPPORTED_FILE_TYPE` |
| 异步执行 | 任务保存后在事务提交后提交异步处理 |

成功响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task_1777520000001",
    "kbId": "kb_001",
    "sourceType": "UPLOAD",
    "status": "PENDING",
    "totalCount": 2,
    "successCount": 0,
    "failureCount": 0,
    "runningCount": 0,
    "createdAt": "2026-06-08T10:00:00",
    "updatedAt": "2026-06-08T10:00:00",
    "finishedAt": null,
    "items": [
      {
        "itemId": "item_1777520000002",
        "assetId": "doc_1777520000003",
        "fileName": "合同-付款条款.pdf",
        "fileHash": "md5-text-contract-001",
        "sourceUrl": null,
        "stage": "UPLOAD",
        "status": "PENDING",
        "progress": 0,
        "dedupeResult": "NEW",
        "errorCode": null,
        "errorMessage": null,
        "updatedAt": "2026-06-08T10:00:00",
        "finishedAt": null
      }
    ]
  },
  "timestamp": 1777520000000
}
```

去重跳过响应 item 示例：

```json
{
  "itemId": "item_1777520000004",
  "assetId": "doc_existing_001",
  "fileName": "合同-付款条款.pdf",
  "fileHash": "md5-text-contract-001",
  "sourceUrl": null,
  "stage": "ASKABLE",
  "status": "SKIPPED",
  "progress": 100,
  "dedupeResult": "SKIPPED",
  "errorCode": null,
  "errorMessage": null,
  "updatedAt": "2026-06-08T10:00:00",
  "finishedAt": "2026-06-08T10:00:00"
}
```

### 4.2 查询入库任务列表

```http
GET /api/v1/kbs/{kbId}/ingestion-tasks?status=PENDING&limit=20
X-Access-Token: <access-token>
```

查询参数：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `status` | string | 否 | - | 按任务状态过滤，可选 `PENDING`、`RUNNING`、`SUCCESS`、`PARTIAL_SUCCESS`、`FAILED` |
| `limit` | integer | 否 | 20 | 小于等于 0 时按 20 处理，最大 100 |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "items": [
      {
        "taskId": "task_1777520000001",
        "kbId": "kb_001",
        "sourceType": "UPLOAD",
        "status": "RUNNING",
        "totalCount": 2,
        "successCount": 1,
        "failureCount": 0,
        "runningCount": 1,
        "failureReason": null,
        "createdAt": "2026-06-08T10:00:00",
        "updatedAt": "2026-06-08T10:01:00"
      }
    ],
    "nextCursor": null
  },
  "timestamp": 1777520000000
}
```

### 4.3 查询入库任务详情

```http
GET /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}
X-Access-Token: <access-token>
```

响应体同 `IngestionTaskDTO`。

### 4.4 重试任务内全部失败 item

```http
POST /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed
X-Access-Token: <access-token>
```

处理规则：

| 规则 | 说明 |
|---|---|
| 权限 | 需要导入权限 |
| 失败 item | 任务内必须存在 `FAILED` item，否则返回 `INGEST_NO_FAILED_ITEMS` |
| 状态重置 | 失败 item 被重置为待处理状态后重新提交异步处理 |

响应体同 `IngestionTaskDTO`。

### 4.5 重试单个失败 item

```http
POST /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/items/{itemId}/retry
X-Access-Token: <access-token>
```

处理规则：

| 规则 | 说明 |
|---|---|
| 权限 | 需要导入权限 |
| item 存在性 | `itemId` 必须属于当前任务 |
| item 状态 | 仅允许重试 `FAILED` item，否则返回 `INGEST_RETRY_ONLY_FAILED` |
| 状态重置 | 指定 item 被重置为待处理状态后重新提交异步处理 |

响应体同 `IngestionTaskDTO`。

### 4.6 创建文档重新解析任务

```http
POST /api/v1/kbs/{kbId}/documents/{assetId}/reparse
X-Access-Token: <access-token>
```

处理规则：

| 规则 | 说明 |
|---|---|
| 权限 | 需要导入权限 |
| 文档存在性 | `assetId` 必须属于当前知识库且未删除 |
| 任务类型 | 创建 `sourceType=REPARSE` 的维护任务 |
| 初始阶段 | item 阶段为 `PARSE`，初始进度为 20 |
| 文档状态 | 将文档 `parseStatus` 和 `indexStatus` 置为 `PENDING` |

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task_1777520000100",
    "assetId": "doc_1777520000003",
    "status": "PENDING"
  },
  "timestamp": 1777520000000
}
```

### 4.7 创建文档重新向量化任务

```http
POST /api/v1/kbs/{kbId}/documents/{assetId}/reembed
X-Access-Token: <access-token>
```

处理规则：

| 规则 | 说明 |
|---|---|
| 权限 | 需要导入权限 |
| 文档存在性 | `assetId` 必须属于当前知识库且未删除 |
| 任务类型 | 创建 `sourceType=REEMBED` 的维护任务 |
| 初始阶段 | item 阶段为 `EMBED`，初始进度为 60 |
| 文档状态 | 保持当前 `parseStatus`，将 `indexStatus` 置为 `PENDING` |

响应体同 `DocumentMaintenanceTaskDTO`。

## 5. 异步处理流程

### 5.1 文本类资产

文本类资产由 `KbIngestionTaskProcessorImpl.processText` 处理：

```text
PARSE -> CHUNK -> EMBED -> INDEX -> ASKABLE
```

处理步骤：

1. 转成 `TextAssetMetadata`。
2. 对远程资源补充元数据。
3. 通过 `TextParserRouter` 选择 parser。
4. 解析为 `TextParseResult`。
5. 通过 `TextChunkSplitter` 切 chunk。
6. 对每个 chunk 生成文本 embedding。
7. 通过 `TextSegmentRepository` 写入统一 `kb_segment`。
8. 更新文档和 item 为成功。

### 5.2 图片类资产

图片类资产由 `KbIngestionTaskProcessorImpl.processImage` 处理：

```text
PARSE -> INDEX -> ASKABLE
```

处理步骤：

1. 根据 `objectKey` 构造 AI 可访问图片输入。
2. 生成图片 embedding。
3. 提取结构化 OCR。
4. 生成 tags 和 graph。
5. 构建 `IMAGE_CAPTION` 和 `IMAGE_OCR_BLOCK` segments。
6. 写入统一 `kb_segment`。
7. 更新文档和 item 为成功。

### 5.3 进度值

| 阶段 | 当前实现进度 |
|---|---:|
| URL 初始待解析 | 10 |
| `PARSE` | 20 |
| `CHUNK` | 40 |
| `EMBED` | 65 |
| `INDEX` | 85 |
| `ASKABLE` | 100 |

## 6. 前端接入建议

1. 导入页先调用 `/api/v1/ingestion/capabilities` 获取支持格式、最大文件大小、单批数量和去重策略。
2. 用户提交导入时调用 `POST /api/v1/kbs/{kbId}/ingestion-tasks`。
3. 创建成功后轮询 `GET /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}`。
4. 根据 item 的 `stage`、`status`、`progress` 渲染进度。
5. 对 `FAILED` item 展示 `errorMessage`，允许调用单项重试。
6. 文档详情页或管理页可以提供 `reparse` / `reembed` 维护操作。

## 7. 与旧入库接口的关系

| 接口 | 定位 | 是否主链路 |
|---|---|---|
| `/api/v1/kbs/{kbId}/ingestion-tasks` | 知识库维度统一入库，DB 持久化任务和文档状态 | 是 |
| `/api/v1/image/batch-tasks` | 旧图片批任务，Redis task，图片专项 | 否 |
| `/api/v1/ingestion/text-assets/batch-tasks` | 旧文本资产批任务，Redis task，文本专项 | 否 |

长期建议对前端只暴露知识库统一入库接口，旧接口保留为兼容、调试或逐步下线。
