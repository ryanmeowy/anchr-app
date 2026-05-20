# ES CLI MVP 方案：查询封装与后端契约

更新时间：2026-04-10

## 1. 背景与目标

- 背景：Elasticsearch 原生 `curl` 查询编写复杂，参数冗长、易出错，团队协作时命令不统一。
- 目标：通过“后端白名单 API + 轻量 CLI”方式，封装高频查询能力，提升查询效率与可维护性。

## 2. 结论（是否可行 / 是否有必要）

- 可行：技术实现成本可控，且与现有后端架构兼容。
- 有必要：在以下场景下收益明显：
- 高频运维与排查（集群健康、索引状态、文档抽样）
- 团队多人协作，需统一查询口径与输出格式
- 需要权限控制、审计与风控（避免直接暴露 ES）

## 3. 范围定义

- 边界内（MVP）：
- 集群状态查询
- 索引列表与索引状态查询
- 单文档查询与基础条件检索
- 映射信息查询
- CLI 输出格式化（table/json）

- 边界外（后续迭代）：
- 全量 DSL 透传
- 写操作（delete/update/reindex）
- 跨集群复杂聚合分析

## 4. 架构方案

1. CLI 仅负责：参数解析、调用后端 API、结果展示（table/json）、用户认证透传。
2. 后端负责：封装 ES 查询、参数校验、权限控制、审计日志、统一错误码。
3. ES 直连仅保留在后端，CLI 不直接持有 ES 凭证。

## 5. CLI 命令设计（MVP）

建议命令名称：`sv-es`（可按项目实际命名调整）。

1. `sv-es cluster health`
- 说明：查看集群健康状态。
- 常用参数：`--format table|json`

2. `sv-es cluster stats`
- 说明：查看集群核心统计信息（节点数、分片、存储等）。

3. `sv-es index list`
- 说明：列出索引及基础状态。
- 常用参数：`--pattern <wildcard>`、`--status <green|yellow|red>`

4. `sv-es index stats <index>`
- 说明：查看指定索引文档数、大小、主副分片信息。

5. `sv-es mapping get <index>`
- 说明：查看索引 mapping 结构。

6. `sv-es doc get <index> <id>`
- 说明：按主键获取文档。
- 常用参数：`--source true|false`

7. `sv-es doc search <index>`
- 说明：执行受控的基础搜索。
- 常用参数：`--q "field:value"`、`--size 10`、`--from 0`、`--sort "field:desc"`

## 6. 后端 API Contract（MVP）

统一前缀建议：`/api/es`

### 6.1 集群状态

1. `GET /api/es/cluster/health`
- Query：`level`（可选，默认 `cluster`）
- Response:
```json
{
  "status": "green",
  "clusterName": "anchr-es",
  "numberOfNodes": 3,
  "activePrimaryShards": 24,
  "activeShards": 48,
  "unassignedShards": 0,
  "timedOut": false,
  "timestamp": "2026-04-10T10:00:00Z"
}
```

2. `GET /api/es/cluster/stats`
- Query：无
- Response：节点、索引、存储、分片等摘要信息。

### 6.2 索引能力

1. `GET /api/es/indices`
- Query：`pattern`、`status`、`page`、`size`
- Response:
```json
{
  "items": [
    {
      "name": "documents-2026-04",
      "health": "green",
      "docsCount": 123456,
      "storeSize": "3.4gb",
      "pri": 3,
      "rep": 1
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1
}
```

2. `GET /api/es/indices/{index}/stats`
- Response：索引级别文档数、段信息、refresh/merge 等统计。

3. `GET /api/es/indices/{index}/mapping`
- Response：mapping 结构（可选裁剪版，避免超大响应）。

### 6.3 文档能力

1. `GET /api/es/indices/{index}/docs/{id}`
- Query：`source`（默认 `true`）
- Response:
```json
{
  "found": true,
  "index": "documents-2026-04",
  "id": "abc123",
  "version": 7,
  "source": {
    "title": "example",
    "content": "..."
  }
}
```

2. `POST /api/es/indices/{index}/search`
- Request:
```json
{
  "query": "title:vision AND tags:ai",
  "from": 0,
  "size": 10,
  "sort": ["_score:desc", "createdAt:desc"],
  "sourceIncludes": ["title", "tags", "createdAt"]
}
```
- Response:
```json
{
  "took": 12,
  "timedOut": false,
  "total": 234,
  "hits": [
    {
      "id": "abc123",
      "score": 1.23,
      "source": {
        "title": "example"
      }
    }
  ]
}
```

## 7. 安全与风控要求

1. 白名单字段与语法：`doc search` 仅允许受控查询语法，禁止任意脚本与危险 DSL。
2. 请求限制：默认 `size <= 100`，超限直接拒绝。
3. 超时控制：后端统一设置查询超时（如 3~5 秒）。
4. 鉴权：复用现有登录态/Token，按角色限制可访问索引。
5. 审计：记录调用人、命令、参数摘要、耗时、结果状态。

## 8. 错误码规范（建议）

- `ESCLI-4001` 参数非法（如 size 超限）
- `ESCLI-4002` 非法查询语法
- `ESCLI-4003` 无索引访问权限
- `ESCLI-4004` 索引不存在
- `ESCLI-5001` ES 查询超时
- `ESCLI-5002` ES 服务不可用

返回体建议：
```json
{
  "code": "ESCLI-4001",
  "message": "size must be between 1 and 100",
  "requestId": "9f2c..."
}
```

## 9. 实施计划（两周建议）

1. 第 1-2 天：冻结 MVP 命令范围与 API 字段。
2. 第 3-5 天：后端 API 实现（cluster/index/doc 三类）。
3. 第 6-7 天：CLI 参数解析 + table/json 输出。
4. 第 8-9 天：鉴权、审计、限流与超时策略。
5. 第 10 天：联调与文档完善。

## 10. 验收标准（DoD）

1. CLI 支持本方案定义的 7 个 MVP 命令。
2. 常见排查场景可在 1 分钟内完成查询，不依赖手写 ES `curl`。
3. 查询均经过后端鉴权与审计，且无直接 ES 凭证暴露。
4. 命令错误具备稳定错误码与可读提示。
5. 提供至少 1 份使用说明（README 或 docs 补充）。

## 11. 后续演进建议

1. 增加 `--profile` 支持多环境（dev/staging/prod）切换。
2. 增加 `sv-es explain`（解释查询计划/命中原因）。
3. 按业务域沉淀子命令（如 `sv-es biz order search`）。
4. 提供只读“raw query”模式（强权限 + 审计）。
