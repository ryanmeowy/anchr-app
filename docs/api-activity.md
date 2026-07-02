# Activity API

Base path: `/api/v1/activity`

所有接口均标记 `@RequireAuth`，调用方需要在请求头携带有效 `X-Access-Token`。

---

## GET /api/v1/activity/recent-citations

查询当前用户最近的引用打开记录，按时间倒序分页返回。

### Request

**Headers:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `X-Access-Token` | string | 是 | 访问 token |

**Query parameters:**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `limit` | int | 否 | 10 | 每页条数（1-50） |
| `cursor` | string | 否 | - | 分页游标（base64 编码的 offset），首次不传 |

### Response

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "items": [
      {
        "recordId": "evt_abc123",
        "segmentId": "seg_xyz789",
        "assetId": "asset_001",
        "kbId": "kb_001",
        "kbName": "技术文档库",
        "fileName": "docker-compose-guide.pdf",
        "title": "Docker Compose 网络配置",
        "snippet": "在 docker-compose.yml 中定义 networks 字段...",
        "citationReason": "语义和关键词高度匹配，相关度得分 0.92",
        "openedAt": "2025-06-30T14:30:00",
        "sourceType": "SEARCH",
        "sourceId": "turn_001",
        "sessionId": "sess_456",
        "citationIndex": "1",
        "question": "Docker Compose 如何配置网络？"
      }
    ],
    "nextCursor": "MTA="
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `items` | array | 引用记录列表（按 openedAt 倒序） |
| `items[].recordId` | string | 活动事件 ID，可用于 preview 接口的 `recordId` 参数回溯引用上下文 |
| `items[].segmentId` | string | 引用的 Segment ID |
| `items[].assetId` | string \| null | 所属资产 ID |
| `items[].kbId` | string \| null | 所属知识库 ID |
| `items[].kbName` | string \| null | 知识库名称 |
| `items[].fileName` | string \| null | 源文件名 |
| `items[].title` | string \| null | 文档标题 |
| `items[].snippet` | string \| null | 引用片段文本 |
| `items[].citationReason` | string \| null | 引用原因（LLM 生成的自然语言解释） |
| `items[].openedAt` | string | 引用打开时间（ISO 8601） |
| `items[].sourceType` | string \| null | 来源类型（`SEARCH` / `ASK`） |
| `items[].sourceId` | string \| null | 来源 ID（search 时为 turnId） |
| `items[].sessionId` | string \| null | 会话 ID（ASK 时提供） |
| `items[].citationIndex` | string \| null | 引用序号 |
| `items[].question` | string \| null | 用户原始问题 |
| `nextCursor` | string \| null | 下一页游标，为 null 时表示已到末尾 |

### Pagination

- 查询范围为最近一周的 `CITATION_OPENED` 事件
- `cursor` 为 base64 编码的 offset，前端无需解析，透传即可
- 有下一页时 `nextCursor` 非 null，将其作为下轮请求的 `cursor` 参数传入

### Notes

- `recordId` 可用作 `POST /api/v1/preview/segments/{segmentId}` 的 `recordId` 入参，从活动记录中还原完整引用上下文
- `why` 为 JSON 字符串，结构与 `PreviewRequestDTO.CitationInfo.why` 一致
