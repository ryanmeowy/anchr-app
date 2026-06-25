# IngestionCapabilityApiController 接口文档

更新时间：2026-06-08  
状态：Current Implementation  
依据：
- `src/main/java/com/anchr/core/kb/interfaces/rest/ingestion/IngestionCapabilityApiController.java`
- `src/main/java/com/anchr/core/kb/application/ingestion/IngestionCapabilityService.java`
- `src/main/java/com/anchr/core/kb/interfaces/rest/dto/ingestion/IngestionCapabilityDTO.java`

## 1. 通用约定

### 1.1 Base Path

```text
/api/v1/ingestion
```

说明：该 controller 只提供入库能力声明，用于前端导入页动态获取支持格式、文件限制、去重策略和阶段枚举。它不创建任务、不解析文件、不写入 ES；真正的知识库入库任务由 `KnowledgeBaseIngestionApiController` 提供。

### 1.2 认证

接口标记 `@RequireAuth`，需要通过认证拦截器。

请求头：

```http
X-Access-Token: <access-token>
```

### 1.3 通用响应

接口返回统一 `Result<T>` 包装。

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
  "code": 401,
  "message": "The token is invalid or expired, please contact the administrator to refresh it",
  "errorCode": "AUTH_TOKEN_INVALID",
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
| 401 | `AUTH_TOKEN_INVALID` / `UNAUTHORIZED` | token 缺失、无效或过期 |
| 500 | `INTERNAL_ERROR` | 未预期的系统错误 |

## 2. DTO 总览

### 2.1 IngestionCapabilityDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `supportedFormats` | array | 支持的导入格式列表 |
| `maxFileSizeBytes` | long | 单文件最大大小，当前为 `209715200`，约 200MB |
| `maxFilesPerBatch` | integer | 单批最大文件数，当前为 50 |
| `dedupeStrategies` | string[] | 支持的去重策略 |
| `defaultDedupeStrategy` | string | 默认去重策略 |
| `ingestionStages` | string[] | 入库任务阶段枚举 |

### 2.2 SupportedFormatDTO

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileType` | string | 文件类型，提交入库任务时对应 `IngestionTaskCreateItemDTO.fileType` |
| `extensions` | string[] | 支持的文件扩展名，不含点号 |
| `mimeTypes` | string[] | 支持的 MIME type |
| `enabled` | boolean | 当前格式是否启用 |
| `priority` | string | 产品阶段标记，如 `P0`、`P1`、`P2` |

### 2.3 支持格式

当前实现由 `IngestionCapabilityService` 静态声明。

| fileType | extensions | mimeTypes | enabled | priority |
|---|---|---|---:|---|
| `PDF` | `pdf` | `application/pdf` | true | `P0` |
| `TXT` | `txt` | `text/plain` | true | `P0` |
| `MD` | `md`, `markdown` | `text/markdown`, `text/x-markdown` | true | `P0` |
| `DOCX` | `docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | true | `P1` |
| `XLSX` | `xlsx`, `xls` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/vnd.ms-excel` | true | `P1` |
| `CSV` | `csv` | `text/csv`, `application/csv` | true | `P1` |
| `HTML` | `html`, `htm` | `text/html`, `application/xhtml+xml` | true | `P1` |
| `URL` | 空数组 | `text/html` | true | `P1` |
| `PPTX` | `pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | true | `P2` |
| `ZIP` | `zip` | `application/zip`, `application/x-zip-compressed` | true | `P2` |
| `IMAGE` | `png`, `jpg`, `jpeg`, `webp` | `image/png`, `image/jpeg`, `image/webp` | true | `P0` |

### 2.4 去重策略

| 值 | 说明 |
|---|---|
| `SKIP` | 同知识库内相同 `fileHash` 已存在时跳过 |
| `OVERWRITE` | 覆盖导入策略标记 |
| `VERSIONED` | 版本化导入策略标记 |

当前默认值：

```text
SKIP
```

### 2.5 入库阶段

| 值 | 说明 |
|---|---|
| `UPLOAD` | 上传/待接收文件 |
| `PARSE` | 解析，文本解析或图片 OCR/视觉处理 |
| `CHUNK` | 文本切块 |
| `EMBED` | 向量化 |
| `INDEX` | 写入索引 |
| `ASKABLE` | 可问答 |

## 3. 接口列表

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/ingestion/capabilities` | 查询当前入库能力声明 |

## 4. 接口详情

### 4.1 查询当前入库能力声明

```http
GET /api/v1/ingestion/capabilities
X-Access-Token: <access-token>
```

请求参数：无。

响应：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "supportedFormats": [
      {
        "fileType": "PDF",
        "extensions": ["pdf"],
        "mimeTypes": ["application/pdf"],
        "enabled": true,
        "priority": "P0"
      },
      {
        "fileType": "IMAGE",
        "extensions": ["png", "jpg", "jpeg", "webp"],
        "mimeTypes": ["image/png", "image/jpeg", "image/webp"],
        "enabled": true,
        "priority": "P0"
      }
    ],
    "maxFileSizeBytes": 209715200,
    "maxFilesPerBatch": 50,
    "dedupeStrategies": ["SKIP", "OVERWRITE", "VERSIONED"],
    "defaultDedupeStrategy": "SKIP",
    "ingestionStages": ["UPLOAD", "PARSE", "CHUNK", "EMBED", "INDEX", "ASKABLE"]
  },
  "timestamp": 1777520000000
}
```

完整 `supportedFormats` 以服务端返回为准。上面的响应示例只展示部分格式。

## 5. 前端接入建议

1. 导入页初始化时调用本接口。
2. 用 `supportedFormats` 生成上传控件的 accept 配置、格式提示和 URL 导入入口。
3. 用 `maxFileSizeBytes` 和 `maxFilesPerBatch` 做前端预校验。
4. 用 `dedupeStrategies` 和 `defaultDedupeStrategy` 渲染去重策略下拉框。
5. 用 `ingestionStages` 或固定映射渲染任务进度条。
6. 创建任务时，把用户选择的 `fileType`、`dedupeStrategy` 传给 `/api/v1/kbs/{kbId}/ingestion-tasks`。

## 6. 与知识库入库接口的关系

| 接口 | 定位 | 是否处理入库任务 |
|---|---|---:|
| `/api/v1/ingestion/capabilities` | 查询入库能力声明 | 否 |
| `/api/v1/kbs/{kbId}/ingestion-tasks` | 创建、查询、重试知识库入库任务 | 是 |

本接口当前返回静态能力。后续如果接入 Provider 动态开关、License 限制、工作区配置或模型能力差异，可以继续沿用该接口作为前端导入页的唯一能力来源。
