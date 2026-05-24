# Phase4 实现记录

## 1. 范围

本文记录 Phase4 当前实现与文档约定的关键差异、降级策略和验收状态，用于 Q4-47 文档同步。

## 2. 已实现能力

| Epic | 能力 | 状态 |
|---|---|---|
| E0 | MySQL + Flyway + MyBatis、鉴权、固定用户上下文 | 已实现 |
| E1 | 知识库、文档资产、文档列表、删除、hash | 已实现 |
| E2 | 统一导入任务、任务状态、重试入口、导入能力声明 | 已实现 |
| E3 | kb 范围搜索、生成答案、对话、引用预览、neighbors | 已实现 |
| E4 | 首页聚合、最近问题、最近引用 | 已实现 |
| E5 | 设置、provider 查询、连接测试、偏好 | 已实现 |
| E6 | DOCX/XLSX/CSV/URL/PPTX/ZIP 解析接入 | 已实现 |
| E7 | 本地账号、Workspace、SSO 基础映射、审计、web search 降级 | 已实现 |
| E8 | 验收文档、联调清单、E2E 样例、性能记录 | 已实现 |

## 3. 重要实现说明

### 3.1 URL 导入

URL 是来源形态，不等于 HTML。

当前判断顺序：

1. 调用方传入 `fileType/mimeType`
2. URL path 扩展名
3. 响应头 `Content-Type`
4. 响应头 `Content-Disposition filename`
5. 无法判断扩展时按网页 HTML 解析

同时保留基础 SSRF 防护，禁止内网、本机、link-local、multicast 地址。

### 3.2 OIDC SSO

当前实现是基础身份映射：

- 支持 `idToken` claims 解码。
- 根据 `iss + sub` 映射 `user_account.external_issuer/external_subject`。
- 可绑定已有 email 用户。
- 失败写入 `SSO_LOGIN_FAILED` 审计。

未做生产级 OIDC client 能力：

- JWKS 拉取
- id_token 签名校验
- authorization code 换 token
- state/nonce 校验

### 3.3 Web Search

当前提供 `POST /api/v1/search/web` 降级接口。

未配置 provider 时返回：

```json
{
  "enabled": false,
  "reason": "Web search provider not configured."
}
```

真实外部搜索 adapter 后续按 provider 单独接入。

### 3.4 入库 Processor

统一 DB 任务已创建 task/item/document_asset。

历史 Redis 文本/图片处理链路仍存在，完整“DB task -> processor -> DB 状态推进”需要后续进一步收口；当前 E8 验收文档按可见 API 合约记录。

## 4. 验证记录

| 验证 | 结果 |
|---|---|
| `mvn -q -DskipTests compile` | 通过 |
| `git diff --check` | 通过 |
| 运行时 E2E | 待在 MySQL/Redis/ES/provider 可用环境执行 |

## 5. 文档同步清单

| 文档 | 状态 |
|---|---|
| PRD | 已按实现记录补充差异 |
| 技术方案 | E8 资产已补齐 |
| DB 表结构 | V1-V4 migration 已落地 |
| 任务卡 | Q4-00 ~ Q4-47 已按实现状态更新 |
| HTTP 样例 | 已提供 `.http` 文件 |
