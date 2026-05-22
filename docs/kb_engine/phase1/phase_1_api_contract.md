# Phase 1 API Contract（M1 冻结版）

## 1. 文本上传

- Method: `POST`
- Path: `/api/v1/ingestion/text-assets/batch-tasks`
- Content-Type: `application/json`

### Request

- `items[]` (required, max 20):
  - `key` (required): OSS object key（前端直传后返回）
  - `fileName` (required): 原文件名
  - `fileHash` (required): 文件 MD5（前端计算）
  - `title` (optional): 自定义标题
  - `mimeType` (optional): 浏览器上报的 mime type

### Response (`200`)

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "f875ec4a-f8ec-4f4a-b965-fb57db8cd736",
    "status": "PENDING",
    "total": 2,
    "successCount": 0,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 2,
    "items": [
      {
        "itemId": "1000000001",
        "key": "kb/text/mysql-notes.md",
        "fileName": "mysql-notes.md",
        "fileHash": "md5-xxx",
        "status": "PENDING"
      }
    ]
  }
}
```

### Errors

- `TEXT_BATCH_ITEMS_REQUIRED` (400)
- `TEXT_FILE_TYPE_NOT_SUPPORTED` (400)

## 2. 文本任务状态

- Method: `GET`
- Path: `/api/v1/ingestion/text-assets/batch-tasks/{taskId}`

### Response (`200`)

与现有 `BatchTaskStatusDTO` 对齐：

- `taskId/status/total/successCount/failureCount/runningCount/pendingCount/createdAt/updatedAt/completedAt/items[]`

### Errors

- `TEXT_TASK_NOT_FOUND` (404)

## 3. 文本任务重试（单项）

- Method: `POST`
- Path: `/api/v1/ingestion/text-assets/batch-tasks/{taskId}/items/{itemId}/retry`

### Errors

- `TEXT_TASK_NOT_FOUND` (404)
- `INGEST_RETRY_ONLY_FAILED` (409)
- `INGEST_TASK_RUNNING` (409)

## 4. 文本任务重试（失败项全量）

- Method: `POST`
- Path: `/api/v1/ingestion/text-assets/batch-tasks/{taskId}/retry-failed`

### Errors

- `TEXT_TASK_NOT_FOUND` (404)
- `INGEST_NO_FAILED_ITEMS` (409)
- `INGEST_TASK_RUNNING` (409)

## 5. 说明

- M1 仅冻结上传与任务框架契约。
- 真实 PDF/TXT/MD 解析及 chunk 生成在 M2 完成。
