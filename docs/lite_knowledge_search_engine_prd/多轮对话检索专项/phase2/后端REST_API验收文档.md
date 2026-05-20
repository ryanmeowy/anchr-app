# 后端 REST API 验收文档

更新时间：2026-05-20  
适用范围：跳过 E3 Streamlit MVP 后，用接口测试验收后端能力闭环，并作为 E4 React/Next.js 联调输入。  
服务地址示例：`http://localhost:8080`

## 1. 验收目标

本验收不覆盖所有历史接口，只覆盖 E4 前必须稳定的后端能力：

1. 上传凭证与轻量鉴权可用。
2. 文本资产可入库、解析、切 chunk、生成 embedding、写入 `kb_segment`。
3. 图片资产可入库、OCR、生成 embedding、写入 gallery 与 `kb_segment`。
4. 统一知识库搜索可召回文本、图片 caption、图片 OCR segment，并返回统一协议。
5. 对话接口可创建会话、发送消息、返回 answer、citations、resultCards、retrievalTrace、suggestedQuestions。
6. 历史消息可回放，且 `resultCards` 从历史 turn 读取，不依赖二次检索。
7. 预览接口可按 `segmentId` 返回预览信息、anchor、surroundingChunks。
8. 旧关键词检索入口不回归。

## 2. 通用约定

### 2.1 通用响应

所有接口使用 `Result<T>` 包装：

```json
{
  "code": 200,
  "message": "Success",
  "data": {},
  "timestamp": 1780000000000,
  "errorId": null
}
```

错误响应示例：

```json
{
  "code": 401,
  "message": "The token is invalid or expired",
  "data": null,
  "timestamp": 1780000000000,
  "errorId": "8b8c3a2c9d7e"
}
```

### 2.2 请求头

普通业务接口：

```http
Content-Type: application/json
X-Access-Token: 123456
```

管理接口：

```http
X-Admin-Secret: local-admin-secret
```

### 2.3 验收变量

接口测试时建议维护以下变量：

| 变量 | 来源 |
|---|---|
| `baseUrl` | 本地或测试环境地址，例如 `http://localhost:8080` |
| `accessToken` | `GET /api/v1/auth/refresh-token` 返回 |
| `textTaskId` | 文本入库任务返回 |
| `imageTaskId` | 图片入库任务返回 |
| `sessionId` | 创建会话返回 |
| `turnId` | 发送消息返回 |
| `segmentId` | 搜索或对话 resultCards 中的 `primaryHit.segmentId` |

## 3. 推荐验收顺序

1. 刷新访问 token。
2. 获取上传凭证。
3. 准备已上传到 OSS 的文本和图片 object key。
4. 提交文本入库任务并轮询到 `SUCCESS` 或 `PARTIAL_FAILED`。
5. 提交图片入库任务并轮询到 `SUCCESS` 或 `PARTIAL_FAILED`。
6. 调用统一知识库搜索 `/api/v1/search/kb`，确认能命中文本和图片 segment。
7. 创建会话并发送问题，确认返回 answer 与 resultCards。
8. 使用 resultCards 的 `primaryHit.segmentId` 调用预览接口。
9. 拉取会话列表和消息历史，确认历史可恢复。
10. 调用旧关键词检索入口，确认不回归。

## 4. 认证与上传凭证

### 4.1 刷新访问 Token

```http
GET /api/v1/auth/refresh-token?code=123456
X-Admin-Secret: local-admin-secret
```

说明：

- `code` 可选；传入时使用指定 token，便于接口测试固定变量。
- 不传 `code` 时后端生成 6 位随机 token。
- 返回 token 用于后续 `X-Access-Token`。

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": "123456",
  "timestamp": 1780000000000,
  "errorId": null
}
```

验收点：

- 正确 `X-Admin-Secret` 返回 200。
- 错误 `X-Admin-Secret` 返回 403。
- 后续带 `X-Access-Token: 123456` 的受保护接口可访问。

### 4.2 获取上传 STS 凭证

```http
GET /api/v1/auth/sts
X-Access-Token: 123456
```

说明：

- 用于前端直传 OSS。
- 如果接口测试不做真实上传，可跳过此接口，直接使用已准备好的 OSS object key。

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": "{\"accessKeyId\":\"STS.xxx\",\"accessKeySecret\":\"xxx\",\"securityToken\":\"xxx\",\"bucket\":\"anchr-dev\",\"region\":\"oss-cn-hangzhou\"}",
  "timestamp": 1780000000000,
  "errorId": null
}
```

验收点：

- 缺少或错误 token 返回 401。
- 成功响应不在日志中打印完整敏感凭证。

## 5. 文本入库接口

### 5.1 提交文本入库任务

```http
POST /api/v1/ingestion/text-assets/batch-tasks
Content-Type: application/json
X-Access-Token: 123456
```

请求示例：

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
    "key": "text-assets/manual/mysql-note.md",
    "fileName": "mysql-note.md",
    "fileHash": "md5-text-mysql-001",
    "title": "MySQL 运维笔记",
    "mimeType": "text/markdown"
  }
]
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `key` | 是 | OSS object key |
| `fileName` | 是 | 原始文件名，用于类型识别和展示 |
| `fileHash` | 是 | 文件指纹，接口测试可用固定字符串 |
| `title` | 否 | 资产标题 |
| `mimeType` | 否 | 浏览器上传时的 MIME |

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task_text_20260520_001",
    "status": "PENDING",
    "total": 2,
    "successCount": 0,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 2,
    "createdAt": 1780000000000,
    "updatedAt": 1780000000000,
    "completedAt": null,
    "items": [
      {
        "itemId": "asset_text_001",
        "assetType": "TEXT",
        "key": "text-assets/contract/payment-terms.pdf",
        "fileName": "合同-付款条款.pdf",
        "fileHash": "md5-text-contract-001",
        "status": "PENDING",
        "errorMessage": null,
        "retryCount": 0,
        "updatedAt": 1780000000000
      }
    ]
  },
  "timestamp": 1780000000001,
  "errorId": null
}
```

验收点：

- 单次最多 20 个 item。
- PDF/TXT/MD 至少各准备一个样例。
- 任务创建后可通过状态接口轮询。

### 5.2 查询文本入库任务状态

```http
GET /api/v1/ingestion/text-assets/batch-tasks/{textTaskId}
X-Access-Token: 123456
```

成功响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task_text_20260520_001",
    "status": "SUCCESS",
    "total": 2,
    "successCount": 2,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 0,
    "createdAt": 1780000000000,
    "updatedAt": 1780000012000,
    "completedAt": 1780000012000,
    "items": [
      {
        "itemId": "asset_text_001",
        "assetType": "TEXT",
        "key": "text-assets/contract/payment-terms.pdf",
        "fileName": "合同-付款条款.pdf",
        "fileHash": "md5-text-contract-001",
        "status": "SUCCESS",
        "errorMessage": null,
        "retryCount": 0,
        "updatedAt": 1780000011000
      }
    ]
  },
  "timestamp": 1780000013000,
  "errorId": null
}
```

验收点：

- 正常样例最终进入 `SUCCESS`。
- 失败样例进入 `FAILED` 或整体 `PARTIAL_FAILED`，并有 `errorMessage`。
- 成功文本资产后续能通过 `/api/v1/search/kb` 召回。

### 5.3 重试文本任务 item

```http
POST /api/v1/ingestion/text-assets/batch-tasks/{textTaskId}/items/{itemId}/retry
X-Access-Token: 123456
```

验收点：

- 对失败 item 调用后，`retryCount` 增加。
- 成功 item 重试应保持可控，不破坏任务状态。

### 5.4 重试文本任务全部失败 item

```http
POST /api/v1/ingestion/text-assets/batch-tasks/{textTaskId}/retry-failed
X-Access-Token: 123456
```

验收点：

- 只重试失败 item。
- 无失败 item 时接口安全返回当前任务状态。

## 6. 图片入库接口

### 6.1 提交图片入库任务

```http
POST /api/v1/image/batch-tasks
Content-Type: application/json
X-Access-Token: 123456
```

请求示例：

```json
[
  {
    "key": "images/contracts/payment-screenshot.png",
    "fileName": "payment-screenshot.png",
    "fileHash": "md5-image-payment-001"
  },
  {
    "key": "images/architecture/mysql-architecture.jpg",
    "fileName": "mysql-architecture.jpg",
    "fileHash": "md5-image-mysql-001"
  }
]
```

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task_image_20260520_001",
    "status": "PENDING",
    "total": 2,
    "successCount": 0,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 2,
    "createdAt": 1780000100000,
    "updatedAt": 1780000100000,
    "completedAt": null,
    "items": [
      {
        "itemId": "asset_image_001",
        "assetType": "IMAGE",
        "key": "images/contracts/payment-screenshot.png",
        "fileName": "payment-screenshot.png",
        "fileHash": "md5-image-payment-001",
        "status": "PENDING",
        "errorMessage": null,
        "retryCount": 0,
        "updatedAt": 1780000100000
      }
    ]
  },
  "timestamp": 1780000100001,
  "errorId": null
}
```

验收点：

- 图片会写入 gallery 索引和 `kb_segment`。
- OCR paragraph 可生成 `IMAGE_OCR_BLOCK` segment。
- Caption/摘要可生成 `IMAGE_CAPTION` 或可展示内容。
- 有 OCR bbox 的图片，后续搜索和预览能透传 bbox。

### 6.2 查询图片入库任务状态

```http
GET /api/v1/image/batch-tasks/{imageTaskId}
X-Access-Token: 123456
```

成功响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task_image_20260520_001",
    "status": "SUCCESS",
    "total": 2,
    "successCount": 2,
    "failureCount": 0,
    "runningCount": 0,
    "pendingCount": 0,
    "createdAt": 1780000100000,
    "updatedAt": 1780000120000,
    "completedAt": 1780000120000,
    "items": [
      {
        "itemId": "asset_image_001",
        "assetType": "IMAGE",
        "key": "images/contracts/payment-screenshot.png",
        "fileName": "payment-screenshot.png",
        "fileHash": "md5-image-payment-001",
        "status": "SUCCESS",
        "errorMessage": null,
        "retryCount": 0,
        "updatedAt": 1780000118000
      }
    ]
  },
  "timestamp": 1780000121000,
  "errorId": null
}
```

验收点：

- 成功图片后续可通过 `/api/v1/search/kb` 和旧图片搜索入口召回。
- 失败时有明确 `errorMessage`，且可重试。

### 6.3 重试图片任务 item

```http
POST /api/v1/image/batch-tasks/{imageTaskId}/items/{itemId}/retry
X-Access-Token: 123456
```

### 6.4 重试图片任务全部失败 item

```http
POST /api/v1/image/batch-tasks/{imageTaskId}/retry-failed
X-Access-Token: 123456
```

## 7. 统一知识库搜索接口

### 7.1 搜索 KB Segment

```http
POST /api/v1/search/kb
Content-Type: application/json
```

请求示例：

```json
{
  "query": "合同付款期限是多少",
  "topK": 20,
  "limit": 10,
  "strategy": "KB_RRF_RERANK"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `query` | 是 | 自然语言查询，最长 200 |
| `topK` | 否 | 每路召回候选数，1-200 |
| `limit` | 否 | 最终返回数量上限，1-200 |
| `strategy` | 否 | `KB_RRF` 或 `KB_RRF_RERANK` |

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": [
    {
      "segmentType": "TEXT_CHUNK",
      "content": "甲方应在验收合格后30日内完成付款。",
      "resultType": "TEXT",
      "assetType": "TEXT",
      "snippet": "甲方应在验收合格后30日内完成付款。",
      "pageNo": 3,
      "score": 0.91,
      "explain": {
        "strategyEffective": "KB_RRF_RERANK",
        "hitSources": ["VECTOR", "CONTENT"],
        "matchedBy": {
          "vector": true,
          "title": false,
          "content": true,
          "ocr": false
        },
        "textSignals": {
          "semantic": true,
          "keyword": true,
          "pageHit": true,
          "chunkHit": true
        },
        "imageSignals": {
          "vector": false,
          "ocr": false,
          "caption": false,
          "tag": false
        }
      },
      "anchor": {
        "pageNo": 3,
        "chunkOrder": 12,
        "bbox": null,
        "imageWidth": null,
        "imageHeight": null
      },
      "thumbnail": null,
      "ocrSummary": null,
      "totalHits": 2,
      "topChunks": [
        {
          "segmentId": "seg_text_contract_003_012",
          "segmentType": "TEXT_CHUNK",
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
          "sourceRef": "text-assets/contract/payment-terms.pdf",
          "thumbnail": null,
          "ocrSummary": null
        }
      ],
      "segmentId": "seg_text_contract_003_012",
      "assetId": "asset_text_001",
      "sourceRef": "text-assets/contract/payment-terms.pdf"
    },
    {
      "segmentType": "IMAGE_OCR_BLOCK",
      "content": "付款期限：验收后30日内",
      "resultType": "IMAGE",
      "assetType": "IMAGE",
      "snippet": "付款期限：验收后30日内",
      "pageNo": null,
      "score": 0.87,
      "explain": {
        "strategyEffective": "KB_RRF_RERANK",
        "hitSources": ["VECTOR", "OCR"],
        "matchedBy": {
          "vector": true,
          "title": false,
          "content": false,
          "ocr": true
        },
        "textSignals": {
          "semantic": false,
          "keyword": false,
          "pageHit": false,
          "chunkHit": false
        },
        "imageSignals": {
          "vector": true,
          "ocr": true,
          "caption": false,
          "tag": false
        }
      },
      "anchor": {
        "pageNo": null,
        "chunkOrder": null,
        "bbox": {
          "x": 120,
          "y": 240,
          "width": 360,
          "height": 90,
          "unit": "PIXEL"
        },
        "imageWidth": 1440,
        "imageHeight": 1080
      },
      "thumbnail": "https://oss.example.com/thumb/payment-screenshot.png",
      "ocrSummary": "截图中包含付款期限说明。",
      "totalHits": 1,
      "topChunks": [],
      "segmentId": "seg_image_payment_ocr_001",
      "assetId": "asset_image_001",
      "sourceRef": "images/contracts/payment-screenshot.png"
    }
  ],
  "timestamp": 1780000200000,
  "errorId": null
}
```

验收点：

- 至少能召回 1 条 `TEXT_CHUNK`。
- 至少能召回 1 条 `IMAGE_CAPTION` 或 `IMAGE_OCR_BLOCK`。
- `segmentId`、`assetId`、`sourceRef` 不为空。
- 图片 OCR 命中有 bbox 样例时，`anchor.bbox`、`imageWidth`、`imageHeight` 完整。
- `strategy=KB_RRF_RERANK` 且 rerank 开启时，`explain.strategyEffective=KB_RRF_RERANK`。

## 8. 对话检索接口

### 8.1 创建会话

```http
POST /api/conversations
Content-Type: application/json
X-Access-Token: 123456
```

请求示例：

```json
{
  "title": "合同问答"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "conv_20260520_001",
    "userId": "single_user",
    "title": "合同问答",
    "status": "ACTIVE",
    "lastMessagePreview": null,
    "createdAt": 1780000300000,
    "updatedAt": 1780000300000,
    "expiresAt": 1782592300000
  },
  "timestamp": 1780000300001,
  "errorId": null
}
```

验收点：

- 返回 `sessionId`。
- 会话默认归属 `single_user`。

### 8.2 发送消息

```http
POST /api/conversations/{sessionId}/messages
Content-Type: application/json
X-Access-Token: 123456
```

请求示例：

```json
{
  "query": "合同里付款期限是什么？",
  "topK": 20,
  "limit": 10,
  "strategy": "KB_RRF_RERANK"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "conv_20260520_001",
    "turnId": "turn_20260520_001",
    "rewrittenQuery": "合同 付款期限 验收后 付款",
    "answer": "结论：合同约定付款应在验收合格后30日内完成付款。\n\n要点：\n1. 付款触发条件是验收合格。\n2. 付款期限为验收后30日内。[1]",
    "citations": [
      {
        "fileName": "合同-付款条款.pdf",
        "pageNo": 3,
        "snippet": "甲方应在验收合格后30日内完成付款。",
        "hitType": "TEXT_CHUNK",
        "assetId": "asset_text_001",
        "segmentId": "seg_text_contract_003_012"
      }
    ],
    "resultCards": [
      {
        "assetId": "asset_text_001",
        "assetType": "TEXT",
        "fileName": "合同-付款条款.pdf",
        "title": "合同付款条款",
        "score": 0.91,
        "hitCount": 2,
        "primaryHit": {
          "segmentId": "seg_text_contract_003_012",
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
        "additionalHits": [
          {
            "segmentId": "seg_text_contract_003_013",
            "snippet": "乙方应提供验收材料后进入付款流程。",
            "score": 0.82,
            "pageNo": 3,
            "anchor": {
              "pageNo": 3,
              "chunkOrder": 13,
              "bbox": null,
              "imageWidth": null,
              "imageHeight": null
            },
            "hitType": "TEXT_CHUNK"
          }
        ]
      },
      {
        "assetId": "asset_image_001",
        "assetType": "IMAGE",
        "fileName": "payment-screenshot.png",
        "title": "payment-screenshot.png",
        "score": 0.87,
        "hitCount": 1,
        "primaryHit": {
          "segmentId": "seg_image_payment_ocr_001",
          "snippet": "付款期限：验收后30日内",
          "score": 0.87,
          "pageNo": null,
          "anchor": {
            "pageNo": null,
            "chunkOrder": null,
            "bbox": {
              "x": 120,
              "y": 240,
              "width": 360,
              "height": 90,
              "unit": "PIXEL"
            },
            "imageWidth": 1440,
            "imageHeight": 1080
          },
          "hitType": "IMAGE_OCR_BLOCK"
        },
        "additionalHits": []
      }
    ],
    "retrievalTrace": {
      "topK": 20,
      "limit": 10,
      "strategy": "KB_RRF_RERANK",
      "strategyEffective": "KB_RRF_RERANK",
      "rewriteReason": "rewrite_by_model",
      "rewriteConfidence": 0.86,
      "rewriteFallback": false,
      "retrievedCount": 8,
      "groupedResultCounts": {
        "TEXT": 4,
        "IMAGE": 2
      },
      "topSegmentIds": [
        "seg_text_contract_003_012",
        "seg_image_payment_ocr_001"
      ],
      "topHitSources": [
        "VECTOR",
        "CONTENT",
        "OCR"
      ],
      "answerFallback": false,
      "answerFallbackReason": null
    },
    "suggestedQuestions": [
      "验收合格的定义是什么？",
      "逾期付款有什么责任？",
      "付款需要哪些材料？"
    ],
    "createdAt": 1780000320000
  },
  "timestamp": 1780000321000,
  "errorId": null
}
```

验收点：

- `answer` 不为空。
- `resultCards` 最多 3 张 asset 级卡片。
- 每张卡片必须有 `primaryHit.segmentId`。
- 同一 asset 的多个命中进入 `additionalHits`。
- `citations[].segmentId` 能对应至少一个 result card hit。
- `retrievalTrace` 包含 rewrite、召回数量、top segment、fallback 信息。
- 无证据场景应返回 fallback answer，不能编造引用。

### 8.3 获取会话列表

```http
GET /api/conversations?limit=20&cursor=
X-Access-Token: 123456
```

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "items": [
      {
        "sessionId": "conv_20260520_001",
        "userId": "single_user",
        "title": "合同问答",
        "status": "ACTIVE",
        "lastMessagePreview": "合同里付款期限是什么？",
        "createdAt": 1780000300000,
        "updatedAt": 1780000320000,
        "expiresAt": 1782592320000
      }
    ],
    "nextCursor": null
  },
  "timestamp": 1780000330000,
  "errorId": null
}
```

验收点：

- 刚发送过消息的会话排在前面。
- `lastMessagePreview` 更新。
- TTL 内刷新页面后可恢复会话。

### 8.4 获取会话消息历史

```http
GET /api/conversations/{sessionId}/messages?limit=20
X-Access-Token: 123456
```

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "sessionId": "conv_20260520_001",
    "turns": [
      {
        "turnId": "turn_20260520_001",
        "sessionId": "conv_20260520_001",
        "query": "合同里付款期限是什么？",
        "rewrittenQuery": "合同 付款期限 验收后 付款",
        "answer": "结论：合同约定付款应在验收合格后30日内完成付款。",
        "citations": [
          {
            "fileName": "合同-付款条款.pdf",
            "pageNo": 3,
            "snippet": "甲方应在验收合格后30日内完成付款。",
            "hitType": "TEXT_CHUNK",
            "assetId": "asset_text_001",
            "segmentId": "seg_text_contract_003_012"
          }
        ],
        "resultCards": [
          {
            "assetId": "asset_text_001",
            "assetType": "TEXT",
            "fileName": "合同-付款条款.pdf",
            "title": "合同付款条款",
            "score": 0.91,
            "hitCount": 2,
            "primaryHit": {
              "segmentId": "seg_text_contract_003_012",
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
        "createdAt": 1780000320000
      }
    ]
  },
  "timestamp": 1780000340000,
  "errorId": null
}
```

验收点：

- `resultCards` 与发送消息时一致或兼容一致。
- 历史回放不触发新一轮检索。
- 老数据缺少 resultCards 时安全降级为空数组。

### 8.5 获取会话详情

```http
GET /api/conversations/{sessionId}
X-Access-Token: 123456
```

### 8.6 重命名会话

```http
PATCH /api/conversations/{sessionId}
Content-Type: application/json
X-Access-Token: 123456
```

请求示例：

```json
{
  "title": "合同付款问答"
}
```

### 8.7 删除会话

```http
DELETE /api/conversations/{sessionId}
X-Access-Token: 123456
```

验收点：

- 删除后 `GET /api/conversations/{sessionId}` 返回 404。

## 9. Segment 预览接口

### 9.1 获取 Segment 预览

```http
GET /api/v1/preview/segments/{segmentId}
X-Access-Token: 123456
```

文本/PDF 响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "segmentId": "seg_text_contract_003_012",
    "assetId": "asset_text_001",
    "assetType": "TEXT",
    "segmentType": "TEXT_CHUNK",
    "fileName": "合同-付款条款.pdf",
    "previewType": "PDF",
    "previewUrl": "https://oss.example.com/text-assets/contract/payment-terms.pdf?Expires=1780000900&Signature=***",
    "expiresAt": 1780000900000,
    "sourceRef": "text-assets/contract/payment-terms.pdf",
    "thumbnail": null,
    "title": "合同付款条款",
    "snippet": "甲方应在验收合格后30日内完成付款。",
    "ocrSummary": null,
    "anchor": {
      "pageNo": 3,
      "chunkOrder": 12,
      "bbox": null,
      "imageWidth": null,
      "imageHeight": null
    },
    "surroundingChunks": [
      {
        "segmentId": "seg_text_contract_003_011",
        "chunkOrder": 11,
        "pageNo": 3,
        "content": "乙方提交验收材料后，甲方组织验收。",
        "relation": "PREVIOUS"
      },
      {
        "segmentId": "seg_text_contract_003_012",
        "chunkOrder": 12,
        "pageNo": 3,
        "content": "甲方应在验收合格后30日内完成付款。",
        "relation": "CURRENT"
      },
      {
        "segmentId": "seg_text_contract_003_013",
        "chunkOrder": 13,
        "pageNo": 3,
        "content": "逾期付款应承担违约责任。",
        "relation": "NEXT"
      }
    ]
  },
  "timestamp": 1780000360000,
  "errorId": null
}
```

图片 OCR 响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "segmentId": "seg_image_payment_ocr_001",
    "assetId": "asset_image_001",
    "assetType": "IMAGE",
    "segmentType": "IMAGE_OCR_BLOCK",
    "fileName": "payment-screenshot.png",
    "previewType": "IMAGE",
    "previewUrl": "https://oss.example.com/images/contracts/payment-screenshot.png?Expires=1780000900&Signature=***",
    "expiresAt": 1780000900000,
    "sourceRef": "images/contracts/payment-screenshot.png",
    "thumbnail": "https://oss.example.com/thumb/payment-screenshot.png",
    "title": "payment-screenshot.png",
    "snippet": "付款期限：验收后30日内",
    "ocrSummary": "截图中包含付款期限说明。",
    "anchor": {
      "pageNo": null,
      "chunkOrder": null,
      "bbox": {
        "x": 120,
        "y": 240,
        "width": 360,
        "height": 90,
        "unit": "PIXEL"
      },
      "imageWidth": 1440,
      "imageHeight": 1080
    },
    "surroundingChunks": []
  },
  "timestamp": 1780000370000,
  "errorId": null
}
```

验收点：

- 缺少 `X-Access-Token` 返回 401。
- 不存在的 `segmentId` 返回 404。
- 签名失败返回 500，且不泄露内部异常。
- PDF 至少返回 `pageNo`。
- TXT/MD 至少返回 `surroundingChunks` 或可定位 snippet。
- IMAGE 有有效 bbox 时返回 `bbox + imageWidth + imageHeight`。
- IMAGE bbox 缺失或无效时 `bbox=null`，前端可安全降级。

## 10. 旧关键词检索回归接口

这些接口服务于“关键词检索入口保留”，不是新的对话协议核心，但 E4 正式前端需要保留入口。

### 10.1 图片/关键词搜索

```http
POST /api/v1/vision/search
Content-Type: application/json
```

请求示例：

```json
{
  "keyword": "付款期限",
  "topK": 20,
  "limit": 10,
  "enableOcr": true,
  "searchType": "0"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": [
    {
      "id": "asset_image_001",
      "url": "https://oss.example.com/images/contracts/payment-screenshot.png",
      "score": 0.86,
      "ocrText": "付款期限：验收后30日内",
      "highlights": {
        "ocrText": "<em>付款期限</em>：验收后30日内"
      },
      "filename": "payment-screenshot.png",
      "sortValues": null,
      "tags": ["合同", "付款"],
      "relations": [],
      "vectorHitStatus": "VECTOR_AND_TEXT",
      "explain": {
        "strategyEffective": "HYBRID",
        "hitSources": ["VECTOR", "OCR"]
      }
    }
  ],
  "timestamp": 1780000400000,
  "errorId": null
}
```

验收点：

- `searchType=0` 混合搜索可用。
- `searchType=1` 向量搜索可用。
- `searchType=2` 文本搜索可用。
- `enableOcr=true` 时 OCR 命中能进入结果。

### 10.2 分页关键词搜索

```http
POST /api/v1/vision/search-page
Content-Type: application/json
```

请求示例：

```json
{
  "keyword": "付款期限",
  "topK": 20,
  "limit": 10,
  "enableOcr": true,
  "searchType": "0",
  "cursor": null
}
```

响应示例：

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "items": [
      {
        "id": "asset_image_001",
        "url": "https://oss.example.com/images/contracts/payment-screenshot.png",
        "score": 0.86,
        "ocrText": "付款期限：验收后30日内",
        "filename": "payment-screenshot.png",
        "sortValues": [0.86, "asset_image_001"],
        "tags": ["合同", "付款"],
        "relations": [],
        "vectorHitStatus": "VECTOR_AND_TEXT",
        "explain": {
          "strategyEffective": "HYBRID"
        }
      }
    ],
    "nextCursor": "eyJzb3J0IjpbMC44NiwiYXNzZXRfaW1hZ2VfMDAxIl19",
    "hasMore": true
  },
  "timestamp": 1780000410000,
  "errorId": null
}
```

验收点：

- 首次请求返回 `nextCursor`。
- 使用 `nextCursor` 请求下一页不报错。
- 排序稳定，不重复返回同一页数据。

## 11. 负向验收用例

| 场景 | 请求 | 期望 |
|---|---|---|
| 缺少 token | 任意 `@RequireAuth` 接口 | `code=401` |
| 空 query | `POST /api/conversations/{sessionId}/messages` | `code=400` |
| query 超长 | 对话或 KB 搜索 query 超过 200 字符 | `code=400` |
| 不存在 session | `GET /api/conversations/not_exists` | `code=404` |
| 不存在 segment | `GET /api/v1/preview/segments/not_exists` | `code=404` |
| 入库 object key 不存在 | 提交文本/图片任务 | item 进入 `FAILED`，有 `errorMessage` |
| 签名 URL 失败 | 预览接口 mock OSS 异常 | `code=500`，不泄露完整签名信息 |

## 12. E4 前准入标准

满足以下条件后，可以认为 E3 已被接口验收替代，允许进入 E4：

1. 文本入库至少 3 个样例通过：PDF、TXT、MD。
2. 图片入库至少 3 个样例通过，其中至少 1 个带 OCR bbox。
3. `/api/v1/search/kb` 能返回 TEXT 与 IMAGE 两类结果。
4. `POST /api/conversations/{sessionId}/messages` 至少 5 条样例通过。
5. 每条成功对话响应都包含 `answer`、`resultCards`、`primaryHit.segmentId`、`retrievalTrace`。
6. 至少 1 条无证据问题触发安全 fallback。
7. `GET /api/conversations/{sessionId}/messages` 能回放历史 resultCards。
8. `GET /api/v1/preview/segments/{segmentId}` 覆盖 PDF/TXT/MD/IMAGE。
9. 旧关键词检索 `/api/v1/vision/search` 和 `/api/v1/vision/search-page` 不回归。
10. 负向用例返回符合预期错误码。

