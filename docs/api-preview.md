# Preview API

所有接口均标记 `@RequireAuth`，调用方需要在请求头携带有效 `X-Access-Token`。鉴权由拦截器统一处理，业务接口不接收 token 参数。

## GET /api/v1/preview/segments/{segmentId}

获取单个 segment 的预览元数据，包括短期预览 URL、定位锚点、上下文片段和引用上下文。

### Request

Path parameters:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `segmentId` | string | 是 | 片段 ID，不能为空 |

Headers:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 管理员访问 token，由 `@RequireAuth` 校验 |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "segmentId": "asset-001:chunk:12",
    "assetId": "asset-001",
    "kbId": "kb-001",
    "kbName": "知识库名称",
    "assetType": "PDF",
    "segmentType": "TEXT_CHUNK",
    "fileName": "mysql.pdf",
    "previewType": "PDF",
    "previewUrl": "https://storage.example.com/mysql.pdf?signature=...",
    "expiresAt": 1777520300000,
    "sourceRef": "oss://anchr-dev/mysql.pdf",
    "thumbnail": null,
    "title": "MySQL 架构",
    "snippet": "MySQL 的架构分为 Server 层和存储引擎层...",
    "ocrSummary": null,
    "anchor": {
      "pageNo": 3,
      "chunkOrder": 12,
      "bbox": [{
        "bbox": {
          "l": 0.00,
          "t": 0.00,
          "r": 0.00,
          "b": 0.00,
          "coordOrigin": "BOTTOMLEFT"
        },
        "pageNo": 0
      }],
      "imageWidth": null,
      "imageHeight": null
    },
    "surroundingChunks": [
      {
        "segmentId": "asset-001:chunk:11",
        "chunkOrder": 11,
        "pageNo": 3,
        "content": "上一段正文...",
        "relation": "previous",
        "title": ""
      },
      {
        "segmentId": "asset-001:chunk:12",
        "chunkOrder": 12,
        "pageNo": 3,
        "content": "MySQL 的架构分为 Server 层和存储引擎层...",
        "relation": "current",
        "title": ""
      },
      {
        "segmentId": "asset-001:chunk:13",
        "chunkOrder": 13,
        "pageNo": 3,
        "content": "下一段正文...",
        "relation": "next",
        "title": ""
      }
    ],
    "citationContext": {
      "sourceQuestion": null,
      "answerClaim": null,
      "citationIndex": null,
      "citationReason": "该片段命中当前检索或问答引用，可作为原文证据查看。"
    }
  }
}
```

### Fields

| 字段                               | 类型             | 说明                                       |
|----------------------------------|----------------|------------------------------------------|
| `segmentId`                      | string         | 片段 ID                                    |
| `assetId`                        | string         | 所属资产 ID                                  |
| `kbId`                           | string         | 所属知识库 ID                                 |
| `kbName`                         | string         | 所属知识库 名字                                 |
| `assetType`                      | string         | 资产类型，如 `PDF`、`IMAGE`、`TXT`、`MD`          |
| `segmentType`                    | string         | 片段类型，如 `TEXT_CHUNK`、`IMAGE_OCR_BLOCK`    |
| `fileName`                       | string \| null | 展示文件名，由 `sourceRef` 或标题推断                |
| `previewType`                    | string         | 预览类型，当前来自资产类型                            |
| `previewUrl`                     | string \| null | 短期预览 URL；直连资源返回原始 URL，无 `sourceRef` 时可为空 |
| `expiresAt`                      | long \| null   | `previewUrl` 过期时间，毫秒时间戳；直连 URL 可为空       |
| `sourceRef`                      | string \| null | 源文件引用                                    |
| `thumbnail`                      | string \| null | 缩略图地址或引用                                 |
| `title`                          | string \| null | 标题                                       |
| `snippet`                        | string \| null | 命中片段文本，优先 OCR 文本，其次正文文本，最后标题             |
| `ocrSummary`                     | string \| null | OCR 摘要                                   |
| `anchor`                         | object \| null | 预览定位锚点                                   |
| `anchor.pageNo`                  | int \| null    | 页码                                       |
| `anchor.chunkOrder`              | int \| null    | chunk 序号                                 |
| `anchor.bbox`                    | array \| null  | 图片或页面内定位框                                |
| `anchor.imageWidth`              | int \| null    | 原图宽度                                     |
| `anchor.imageHeight`             | int \| null    | 原图高度                                     |
| `surroundingChunks`              | array          | 前后文片段，最多按当前窗口返回                          |
| `surroundingChunks[].segmentId`  | string         | 前后文片段 ID                                 |
| `surroundingChunks[].chunkOrder` | int \| null    | chunk 序号                                 |
| `surroundingChunks[].pageNo`     | int \| null    | 页码                                       |
| `surroundingChunks[].content`    | string         | 前后文内容，服务端会按字节数截断                         |
| `surroundingChunks[].relation`   | string         | `previous` \| `current` \| `next`        |
| `surroundingChunks[].title`      | string \| null        | 标题        |
| `citationContext`                | object \| null | 引用上下文                                    |
| `citationContext.sourceQuestion` | string \| null | 来源问题，当前可为空                               |
| `citationContext.answerClaim`    | string \| null | 回答断言，当前可为空                               |
| `citationContext.citationIndex`  | int \| null    | 引用序号，当前可为空                               |
| `citationContext.citationReason` | string \| null | 引用原因                                     |

## GET /api/v1/preview/segments/{segmentId}/neighbors

获取预览页使用的邻近文本片段。

### Request

Path parameters:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `segmentId` | string | 是 | 片段 ID，不能为空 |

Query parameters:

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `before` | int | 否 | `3` | 期望向前取的片段数量 |
| `after` | int | 否 | `3` | 期望向后取的片段数量 |

Headers:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 管理员访问 token，由 `@RequireAuth` 校验 |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "segmentId": "asset-001:chunk:12",
    "items": [
      {
        "segmentId": "asset-001:chunk:11",
        "chunkOrder": 11,
        "pageNo": 3,
        "content": "上一段正文...",
        "relation": "previous",
        "title": ""
      },
      {
        "segmentId": "asset-001:chunk:12",
        "chunkOrder": 12,
        "pageNo": 3,
        "content": "当前片段正文...",
        "relation": "current",
        "title": ""
      }
    ]
  }
}
```

### Notes

- 服务端会使用 `max(before, after)` 作为窗口大小。
- 窗口大小最小为 `1`，最大为 `10`。
- 当缺少 `assetId` 或 `chunkOrder` 时，接口会降级返回当前片段。

## POST /api/v1/preview/segments/{segmentId}/refresh

刷新当前认证上下文下的 segment 预览缓存，并重新返回预览元数据。用于短期 `previewUrl` 接近过期、前端显式刷新或需要重新签发 URL 的场景。

### Request

Path parameters:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `segmentId` | string | 是 | 片段 ID，不能为空 |

Headers:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 管理员访问 token，由 `@RequireAuth` 校验 |

### Response

响应结构与 `GET /api/v1/preview/segments/{segmentId}` 相同。

## Error Codes

| 场景 | code | errorCode | message |
|------|------|-----------|---------|
| 缺少或错误的 `X-Access-Token` | `401` | `AUTH_TOKEN_INVALID` | `The token is invalid or expired, please contact the administrator to refresh it` |
| `segmentId` 为空 | `400` | `INVALID_REQUEST` | `segmentId cannot be blank.` |
| segment 不存在 | `404` | `SEGMENT_NOT_FOUND` | `Segment not found` |
| 预览 URL 签发失败 | `500` | `PREVIEW_URL_SIGN_FAILED` | `Failed to sign preview URL` |
| 服务端未找到认证上下文 | `401` | `UNAUTHORIZED` | `Authenticated token context is required.` |

## Cache Behavior

- `GET /segments/{segmentId}` 会优先复用当前认证上下文下的预览缓存。
- 缓存 key 使用 `segmentId + accessTokenHash`，不会保存原始 token。
- 缓存有效期为 `previewUrl` 剩余有效期减去 30 秒安全窗口。
- `POST /segments/{segmentId}/refresh` 只清理当前认证上下文下该 segment 的缓存。
