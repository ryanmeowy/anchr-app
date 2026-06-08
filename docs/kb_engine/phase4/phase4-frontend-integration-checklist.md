# Phase4 前后端联调清单

## 1. 联调变量

| 变量 | 说明 |
|---|---|
| `baseUrl` | 后端 API 地址 |
| `accessToken` | 登录或管理员刷新得到 |
| `kbId` | 当前知识库 |
| `taskId` | 当前导入任务 |
| `assetId` | 当前文档 |
| `segmentId` | 当前引用片段 |

请求头：

```http
X-Access-Token: <token>
Content-Type: application/json
```

## 2. 全局状态

每个页面必须覆盖：

| 状态 | 前端行为 |
|---|---|
| loading | 骨架屏或局部 loading，不阻塞全页导航 |
| empty | 展示可执行的下一步入口 |
| error | 展示错误文案和 retry |
| unauthorized | 引导重新登录或刷新 token |
| forbidden | 展示权限不足，不展示危险操作 |
| retry | 失败导入、预览刷新、请求重试可触达 |

## 3. 页面清单

### 3.1 首页

接口：

- `GET /api/v1/home/summary`
- `GET /api/v1/activity/recent-questions`
- `GET /api/v1/activity/recent-citations`

验收：

- 无知识库时展示空态和创建入口。
- 最近问题、最近引用为空时不报错。
- 最近导入失败时显示失败状态和可重试入口。

### 3.2 知识库页

接口：

- `GET /api/v1/kbs`
- `POST /api/v1/kbs`
- `GET /api/v1/kbs/{kbId}`
- `PATCH /api/v1/kbs/{kbId}`
- `DELETE /api/v1/kbs/{kbId}`
- `GET /api/v1/kbs/{kbId}/documents`
- `DELETE /api/v1/kbs/{kbId}/documents/{assetId}`

验收：

- 分页参数变化不导致列表闪烁错位。
- 删除需要二次确认。
- VIEWER 不展示删除按钮；若后端返回 403，页面显示权限不足。

### 3.3 导入页

接口：

- `GET /api/v1/ingestion/capabilities`
- `POST /api/v1/kbs/{kbId}/ingestion-tasks`
- `GET /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}`
- `POST /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/retry-failed`
- `POST /api/v1/kbs/{kbId}/ingestion-tasks/{taskId}/items/{itemId}/retry`

验收：

- 格式、大小、批次数量来自后端 capabilities。
- URL 导入提示：URL 可以是网页，也可以是文件下载 URL。
- 单个文件失败不影响其他文件。
- ZIP 内部不支持格式展示跳过原因。

### 3.4 搜索页

接口：

- `POST /api/v1/search/kb`
- `POST /api/v1/search/kb-answer`
- `POST /api/v1/search/web`

验收：

- `kbIds` 必须来自当前选择范围。
- 搜索结果分页或 cursor 可继续加载。
- 未配置 web search provider 时前端置灰或显示“未配置”。
- 生成答案失败时搜索结果列表仍可展示。

### 3.5 问答页

接口：

- `POST /api/conversations`
- `GET /api/conversations`
- `GET /api/conversations/{sessionId}`
- `PATCH /api/conversations/{sessionId}`
- `DELETE /api/conversations/{sessionId}`
- `POST /api/conversations/{sessionId}/messages`
- `POST /api/conversations/{sessionId}/messages/stream`
- `GET /api/conversations/{sessionId}/messages`

验收：

- 新会话、历史会话、重命名、删除均可操作。
- 流式接口断开时有 retry。
- 回答引用可点击进入预览页。
- 无引用时明确显示“未找到可引用证据”。

### 3.6 预览页

接口：

- `GET /api/v1/preview/segments/{segmentId}`
- `GET /api/v1/preview/segments/{segmentId}/neighbors`
- `POST /api/v1/preview/segments/{segmentId}/refresh`

验收：

- PDF 页码、文本片段、图片 bbox 均能安全展示。
- bbox 缺失或无效时不绘制错误框。
- `previewUrl` 过期后可刷新。

### 3.7 设置页

接口：

- `GET /api/v1/settings/capabilities`
- `GET /api/v1/settings/providers`
- `GET /api/v1/settings/search`
- `PATCH /api/v1/settings/search`
- `POST /api/v1/settings/test-connection`
- `GET /api/v1/settings/preferences`
- `PATCH /api/v1/settings/preferences`
- `GET /api/v1/account/me`
- `GET /api/v1/workspaces`
- `GET /api/v1/audit-logs`

验收：

- API Key 不明文展示。
- 连接测试失败返回可读原因。
- VIEWER 不展示设置修改入口。
- 审计日志为空时显示空态。

## 4. 联调完成标准

- 6 个主页面均覆盖 loading/empty/error/unauthorized/forbidden/retry。
- P0 主链路可以从首页进入并完成一次创建知识库、导入、搜索、问答、预览。
- 所有错误态均有用户可理解文案。
