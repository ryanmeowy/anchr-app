# Preview API

Base path: `/api/v1/preview`

所有接口均标记 `@RequireAuth`，调用方需要在请求头携带有效 `X-Access-Token`。鉴权由拦截器统一处理，业务接口不接收 token 参数。

---

## POST /api/v1/preview/segments/{segmentId}

获取单个 segment 的预览元数据，包括短期预览 URL、定位锚点、上下文片段和引用上下文。

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `segmentId` | string | 是 | 片段 ID，不能为空 |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token，由 `@RequireAuth` 校验 |

**Request body:**

```json
{
  "recordId": null,
  "sourceType": "SEARCH",
  "sourceId": "turnId",
  "sessionId": "sess_789",
  "question": "Docker 如何安装？",
  "citationInfo": {
    "segmentId": "seg_abc123",
    "citationIndex": "1",
    "why": {
      "score": "0.84",
      "hitSources": ["VECTOR", "TITLE", "CONTENT"],
      "matchSummary": "语义匹配 + 内容关键词命中 (score: 0.84)"
    },
    "reason": ""
  }
}
```

| 字段                              | 类型 | 必填 | 说明                                          |
|---------------------------------|------|------|---------------------------------------------|
| `recordId`                      | string | 否 | 历史引用记录 ID。传入后从活动事件中还原引用信息，忽略 `citationInfo` |
| `sourceType`                    | string | 否 | 来源类型（ASK, SEARCH）                           |
| `sourceId`                      | string | 否 | turnId, search没有                            |
| `sessionId`                     | string | 否 | 会话 ID , ASK时提供                              |
| `question`                      | string | 否 | 用户原始问题                                      |
| `citationInfo`                  | object | 否 | 实时引用信息（`recordId` 为空时生效）                    |
| `citationInfo.segmentId`        | string | 否 | 引用 segment ID                               |
| `citationInfo.citationIndex`    | string | 否 | 引用序号                                        |
| `citationInfo.why`              | object | 否 | 匹配原因                                        |
| `citationInfo.why.score`        | string | 否 | 相关度得分                                       |
| `citationInfo.why.hitSources`   | string[] | 否 | 命中来源列表                                      |
| `citationInfo.why.matchSummary` | string | 否 | 匹配摘要                                        |
| `citationInfo.reason`           | string | 否 | 匹配原因(已总结)                                   |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "segmentId": "asset-001:chunk:12",
    "assetId": "asset-001",
    "kbId": "kb-001",
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
        "bbox": [{
          "bbox": {
            "l": 0.00,
            "t": 0.00,
            "r": 0.00,
            "b": 0.00,
            "coordOrigin": "BOTTOMLEFT"
          },
          "pageNo": 3
        }]
      },
      {
        "segmentId": "asset-001:chunk:12",
        "chunkOrder": 12,
        "pageNo": 3,
        "content": "MySQL 的架构分为 Server 层和存储引擎层...",
        "relation": "current",
        "bbox": [{
          "bbox": {
            "l": 0.00,
            "t": 0.00,
            "r": 0.00,
            "b": 0.00,
            "coordOrigin": "BOTTOMLEFT"
          },
          "pageNo": 3
        }]
      },
      {
        "segmentId": "asset-001:chunk:13",
        "chunkOrder": 13,
        "pageNo": 3,
        "content": "下一段正文...",
        "relation": "next",
        "bbox": [{
          "bbox": {
            "l": 0.00,
            "t": 0.00,
            "r": 0.00,
            "b": 0.00,
            "coordOrigin": "BOTTOMLEFT"
          },
          "pageNo": 3
        }]
      }
    ],
    "citationContext": {
      "citationIndex": "1",
      "citationReason": "语义和关键词高度匹配，相关度得分 0.84"
    },
    "sourceType": "conversation",
    "sourceId": "conv_456",
    "sessionId": "sess_789",
    "sourceQuestion": "Docker 如何安装？"
  }
}
```

### Fields

| 字段 | 类型 | 说明 |
|------|------|------|
| `segmentId` | string | 片段 ID |
| `assetId` | string | 所属资产 ID |
| `kbId` | string | 所属知识库 ID |
| `assetType` | string | 资产类型，如 `PDF`、`IMAGE`、`TXT`、`MD` |
| `segmentType` | string | 片段类型，如 `TEXT_CHUNK`、`IMAGE_OCR_BLOCK` |
| `fileName` | string \| null | 展示文件名，由 `sourceRef` 或标题推断 |
| `previewType` | string | 预览类型，当前来自资产类型 |
| `previewUrl` | string \| null | 短期预览 URL；直连资源返回原始 URL，无 `sourceRef` 时可为空 |
| `expiresAt` | long \| null | `previewUrl` 过期时间，毫秒时间戳；直连 URL 可为空 |
| `sourceRef` | string \| null | 源文件引用 |
| `thumbnail` | string \| null | 缩略图地址或引用 |
| `title` | string \| null | 标题 |
| `snippet` | string \| null | 命中片段文本，优先 OCR 文本，其次正文文本，最后标题 |
| `ocrSummary` | string \| null | OCR 摘要 |
| `anchor` | object \| null | 预览定位锚点，无定位信息时为 null |
| `anchor.pageNo` | int \| null | 页码 |
| `anchor.chunkOrder` | int \| null | chunk 序号 |
| `anchor.bbox` | array \| null | 图片或页面内定位框 |
| `anchor.bbox[].bbox.l` | double | 左边界 |
| `anchor.bbox[].bbox.t` | double | 上边界 |
| `anchor.bbox[].bbox.r` | double | 右边界 |
| `anchor.bbox[].bbox.b` | double | 下边界 |
| `anchor.bbox[].bbox.coordOrigin` | string | 坐标原点 |
| `anchor.bbox[].pageNo` | int | 所在页码 |
| `anchor.imageWidth` | int \| null | 原图宽度 |
| `anchor.imageHeight` | int \| null | 原图高度 |
| `surroundingChunks` | array | 前后文片段，当前窗口 ±1 |
| `surroundingChunks[].segmentId` | string | 前后文片段 ID |
| `surroundingChunks[].chunkOrder` | int \| null | chunk 序号 |
| `surroundingChunks[].pageNo` | int \| null | 页码 |
| `surroundingChunks[].content` | string | 前后文内容，服务端按 UTF-8 字节数截断至 4096 |
| `surroundingChunks[].relation` | string | `previous` \| `current` \| `next` |
| `surroundingChunks[].bbox` | array \| null | 图片或页面内定位框，结构同 `anchor.bbox` |
| `surroundingChunks[].bbox[].bbox.l` | double | 左边界 |
| `surroundingChunks[].bbox[].bbox.t` | double | 上边界 |
| `surroundingChunks[].bbox[].bbox.r` | double | 右边界 |
| `surroundingChunks[].bbox[].bbox.b` | double | 下边界 |
| `surroundingChunks[].bbox[].bbox.coordOrigin` | string | 坐标原点 |
| `surroundingChunks[].bbox[].pageNo` | int | 所在页码 |
| `citationContext` | object \| null | 引用上下文，无 snippet 时为 null |
| `citationContext.citationIndex` | string \| null | 引用序号 |
| `citationContext.citationReason` | string \| null | 引用原因（LLM 生成的自然语言解释，失败时降级为 matchSummary） |
| `sourceType` | string \| null | 来源类型 |
| `sourceId` | string \| null | 来源 ID |
| `sessionId` | string \| null | 会话 ID |
| `sourceQuestion` | string \| null | 用户原始问题 |

---

## GET /api/v1/preview/segments/{segmentId}/neighbors

获取预览页使用的邻近文本片段。

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `segmentId` | string | 是 | 片段 ID，不能为空 |

**Query parameters:**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `before` | int | 否 | `3` | 期望向前取的片段数量 |
| `after` | int | 否 | `3` | 期望向后取的片段数量 |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token，由 `@RequireAuth` 校验 |

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
        "bbox": [{
          "bbox": {
            "l": 0.00,
            "t": 0.00,
            "r": 0.00,
            "b": 0.00,
            "coordOrigin": "BOTTOMLEFT"
          },
          "pageNo": 3
        }]
      },
      {
        "segmentId": "asset-001:chunk:12",
        "chunkOrder": 12,
        "pageNo": 3,
        "content": "当前片段正文...",
        "relation": "current",
        "bbox": [{
          "bbox": {
            "l": 0.00,
            "t": 0.00,
            "r": 0.00,
            "b": 0.00,
            "coordOrigin": "BOTTOMLEFT"
          },
          "pageNo": 3
        }]
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `segmentId` | string | 片段 ID |
| `items` | array | 上下文段落列表 |
| `items[].segmentId` | string | 段落 segment ID |
| `items[].chunkOrder` | int \| null | chunk 序号 |
| `items[].pageNo` | int \| null | 页码 |
| `items[].content` | string | 段落文本（截断至 4096 字节） |
| `items[].relation` | string | `previous` \| `current` \| `next` |
| `items[].bbox` | array \| null | 图片或页面内定位框，结构同 `anchor.bbox` |
| `items[].bbox[].bbox.l` | double | 左边界 |
| `items[].bbox[].bbox.t` | double | 上边界 |
| `items[].bbox[].bbox.r` | double | 右边界 |
| `items[].bbox[].bbox.b` | double | 下边界 |
| `items[].bbox[].bbox.coordOrigin` | string | 坐标原点 |
| `items[].bbox[].pageNo` | int | 所在页码 |

### Notes

- 服务端使用 `max(before, after)` 作为窗口大小。
- 窗口大小最小为 `1`，最大为 `10`。
- 当缺少 `assetId` 或 `chunkOrder` 时，降级返回当前片段。

---

## POST /api/v1/preview/segments/{segmentId}/refresh

刷新当前认证上下文下的 segment 预览缓存，并重新返回预览元数据。用于短期 `previewUrl` 接近过期或前端需要重新签发 URL 的场景。

### Request

**Path parameters:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `segmentId` | string | 是 | 片段 ID，不能为空 |

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token，由 `@RequireAuth` 校验 |

**Request body:** 同 `POST /segments/{segmentId}` 的 `PreviewRequestDTO`

### Response

响应结构与 `POST /segments/{segmentId}` 相同。

---

## Error Codes

| 场景 | code | errorCode | message |
|------|------|-----------|---------|
| 缺少或错误的 `X-Access-Token` | `401` | `AUTH_TOKEN_INVALID` | `The token is invalid or expired, please contact the administrator to refresh it` |
| `segmentId` 为空 | `400` | `INVALID_REQUEST` | `segmentId cannot be blank.` |
| segment 不存在 | `404` | `SEGMENT_NOT_FOUND` | `Segment not found` |
| 预览 URL 签发失败 | `500` | `PREVIEW_URL_SIGN_FAILED` | `Failed to sign preview URL` |
| 服务端未找到认证上下文 | `401` | `UNAUTHORIZED` | `Authenticated token context is required.` |

## Cache Behavior

- `POST /segments/{segmentId}` 会优先复用当前认证上下文下的预览缓存。
- 缓存 key 使用 `segmentId + accessTokenHash`，不会保存原始 token。
- 缓存有效期为 `previewUrl` 剩余有效期减去 30 秒安全窗口。
- `POST /segments/{segmentId}/refresh` 只清理当前认证上下文下该 segment 的缓存。

## Citation Reason 生成机制

`citationContext.citationReason` 由 LLM 根据匹配信息动态生成：

- **输入:** `citationInfo.why` 中的 `score`、`hitSources`、`matchSummary`
- **输出:** ≤30 字的中文自然语言解释
- **降级:** LLM 调用失败时，回退到 `matchSummary` 原文
- **recordId 路径:** 从历史活动事件中还原 `why` 字段后，同样调用 LLM 生成
