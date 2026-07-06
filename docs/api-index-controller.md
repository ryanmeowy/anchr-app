# Index Controller API

> 索引管理接口，管理 `kb_segment` 搜索索引的全生命周期，包括状态查询、索引重试、重建准备与确认。

---

## 概述

| 项目 | 说明 |
|---|---|
| **Base URL** | `/api/v1/index` |
| **Controller** | `com.anchr.core.search.interfaces.rest.IndexController` |
| **服务层** | `SegmentIndexManager` |

---

## 认证

除 `/status` 接口允许 `OWNER` 和 `GUEST` 角色外，其余接口仅允许 `OWNER` 角色访问。认证通过 `@RequireAuth` 注解控制。

---

## 统一响应格式

所有接口返回 `Result<T>` 结构：

```json
{
  "code": 200,
  "message": "Success",
  "errorCode": null,
  "data": { ... },
  "timestamp": 1720345678000,
  "traceId": null,
  "details": null,
  "errorId": null
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | `int` | HTTP 状态码，成功为 `200`，错误为 `500` |
| `message` | `String` | 成功时为 `"Success"`，错误时为错误描述 |
| `errorCode` | `String` | 错误码，成功时为 `null` |
| `data` | `T` | 业务数据，类型由具体接口决定 |
| `timestamp` | `long` | 响应时间戳 (ms) |
| `traceId` | `String` | 链路追踪 ID |
| `details` | `Map` | 附加详情 |
| `errorId` | `String` | 错误关联 ID，用于问题排查 |

---

## 接口列表

### 1. 获取索引状态

查询当前 `kb_segment` 索引的完整状态，包括 ES 索引元数据、嵌入模型配置、重建进度等。

**请求**

```
GET /api/v1/index/status
```

| 角色 | `OWNER`, `GUEST` |

**响应 `Result<SegmentIndexStatusDTO>`**

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "status": "READY",
    "indexExists": true,
    "readable": true,
    "writable": true,
    "actualDim": 768,
    "actualModel": "text-embedding-ada-002",
    "actualProfileFingerprint": "abc123def456",
    "expectedDim": 768,
    "expectedModel": "text-embedding-ada-002",
    "expectedProfileFingerprint": "abc123def456",
    "pendingRebuild": null,
    "rebuildProgress": null,
    "lastError": null
  }
}
```

**data 字段说明**

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | `SegmentIndexStatus` | 索引状态枚举，见 [SegmentIndexStatus](#segmentindexstatus) |
| `indexExists` | `boolean` | ES 中索引是否存在 |
| `readable` | `boolean` | 索引是否可读 |
| `writable` | `boolean` | 索引是否可写 |
| `actualDim` | `Integer` | ES 映射中的实际向量维度 |
| `actualModel` | `String` | ES `_meta` 中记录的实际嵌入模型 |
| `actualProfileFingerprint` | `String` | ES 中记录的实际 profile 指纹 |
| `expectedDim` | `Integer` | 当前活动能力配置的期望维度 |
| `expectedModel` | `String` | 当前活动能力配置的期望模型 |
| `expectedProfileFingerprint` | `String` | 当前配置的期望 profile 指纹 |
| `pendingRebuild` | `PendingRebuild` | 待确认的重建任务，无则为 `null` |
| `rebuildProgress` | `RebuildProgress` | 进行中的重建进度，无则为 `null` |
| `lastError` | `String` | 最近一次错误信息，无则为 `null` |

---

### 2. 重试索引创建

在索引状态为 `NOT_READY` 且有活动嵌入配置时，重新尝试创建索引。

**请求**

```
POST /api/v1/index/retry
```

| 角色 | `OWNER` |
| 请求体 | 无 |

**成功响应 `Result<Boolean>`**

```json
{
  "code": 200,
  "message": "Success",
  "data": true
}
```

**失败响应**

```json
{
  "code": 500,
  "message": "Retry conditions not met: index status must be NOT_READY and active embedding configured",
  "errorCode": "500"
}
```

---

### 3. 准备重建

发起索引重建准备，返回一个 `taskId` 用于后续确认。通常在嵌入模型配置变更导致维度/模型不匹配时调用。

**请求**

```
POST /api/v1/index/rebuild/prepare
```

| 角色 | `OWNER` |
| 请求体 | 无 |

**成功响应 `Result<String>`**

```json
{
  "code": 200,
  "message": "Success",
  "data": "task_20260707_001"
}
```

> `data` 为重建任务 ID，需在下一步 [`POST /rebuild/confirm`](#4-确认重建) 中使用。

---

### 4. 确认重建

使用 `prepareRebuild` 返回的 `taskId` 确认并执行重建操作。

**请求**

```
POST /api/v1/index/rebuild/confirm
```

| 角色 | `OWNER` |
| Content-Type | `application/json` |

```json
{
  "taskId": "task_20260707_001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taskId` | `String` | ✓ | 由 `prepareRebuild` 返回的任务标识，不能为 `null` 或空字符串 |

**成功响应 `Result<Boolean>`**

```json
{
  "code": 200,
  "message": "Success",
  "data": true
}
```

**失败响应**

- 缺少 `taskId`：

```json
{
  "code": 500,
  "message": "taskId is required",
  "errorCode": "500"
}
```

- `taskId` 无效或存在进行中的操作：

```json
{
  "code": 500,
  "message": "Rebuild confirm failed: task not found, or another operation is in progress",
  "errorCode": "500"
}
```

---

## 数据类型

### SegmentIndexStatus

索引生命周期状态枚举：

| 值 | 说明 |
|---|---|
| `NOT_READY` | 索引未就绪，可能尚未创建或配置不完整 |
| `INITIALIZING` | 索引正在初始化中 |
| `READY` | 索引正常可用，可读写 |
| `REBUILDING` | 索引正在重建中（迁移数据 / 切换别名） |

### PendingRebuild

待确认的重建任务信息：

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | `String` | 重建任务 ID |
| `expectedDim` | `int` | 重建后期望的向量维度 |
| `reason` | `String` | 触发重建的原因 |
| `createdAt` | `String` | 任务创建时间 |

### RebuildProgress

进行中重建的进度信息：

| 字段 | 类型 | 说明 |
|---|---|---|
| `migrated` | `long` | 已迁移的文档数 |
| `total` | `long` | 旧索引中的文档总数 |
| `phase` | `String` | 当前阶段，见下表 |

**phase 取值：**

| 值 | 说明 |
|---|---|
| `MIGRATING` | 正在从旧索引迁移文档到新索引 |
| `SWITCHING_ALIAS` | 正在切换别名指向新索引 |
| `COMPLETED` | 重建已完成 |

---

## 典型流程

### 正常查询

```
GET /api/v1/index/status  →  status: "READY"
```

### 重建流程

```
1. GET  /api/v1/index/status
   → 发现 actualDim != expectedDim，需要重建

2. POST /api/v1/index/rebuild/prepare
   → 返回 data: "task_xxx"

3. POST /api/v1/index/rebuild/confirm
   请求体: { "taskId": "task_xxx" }
   → 返回 data: true  (重建已启动)

4. 轮询 GET /api/v1/index/status
   → rebuildProgress.phase: "MIGRATING" → "SWITCHING_ALIAS" → "COMPLETED"
   → 最终 status: "READY"
```

### 重试流程

```
1. GET  /api/v1/index/status  →  status: "NOT_READY"

2. POST /api/v1/index/retry
   → 成功: data: true (初始化已触发)
   → 失败: 错误消息 (条件不满足)
```

---

## 错误码

| code | 场景 |
|---|---|
| `200` | 成功 |
| `500` | 业务逻辑错误（重试条件不满足、taskId 缺失/无效、操作冲突等） |
