# Phase4 性能与稳定性验收

## 1. 指标

| 指标 | 目标 | 接口 |
|---|---:|---|
| 对话首屏 P95 | < 2.5s | `POST /api/conversations/{sessionId}/messages` |
| 预览接口 P95 | < 800ms | `GET /api/v1/preview/segments/{segmentId}` |
| 文档列表 | 分页稳定 | `GET /api/v1/kbs/{kbId}/documents` |
| 搜索接口 | 分页/cursor 稳定 | `POST /api/v1/search/kb` |
| 首页聚合 | 空数据稳定 | `GET /api/v1/home/summary` |

## 2. 执行方法

轻量验收即可，使用固定 20 次请求记录耗时：

```bash
curl -w "%{time_total}\n" -o /tmp/phase4_response.json -s \
  -H "X-Access-Token: ${ACCESS_TOKEN}" \
  "${BASE_URL}/api/v1/kbs/${KB_ID}/documents?page=1&size=20"
```

建议记录：

- count
- min
- max
- p50
- p95
- error count

## 3. 稳定性检查

| 检查项 | 预期 |
|---|---|
| 连续刷新文档列表 | 不重复、不丢页 |
| 搜索重复执行 | 同一索引数据下引用顺序基本稳定 |
| 预览 refresh | 旧 URL 过期后可重新签发 |
| token 过期 | 返回 401，不返回 500 |
| provider 不可用 | 返回可读错误或降级，不阻塞其它接口 |
| URL 导入内网地址 | 被拒绝，避免 SSRF |

## 4. 当前记录

| 日期 | 环境 | 结果 | 备注 |
|---|---|---|---|
| 2026-05-24 | 本地代码编译 | 未执行运行时压测 | 当前仅完成文档和编译级验证，需在 MySQL/Redis/ES/provider 可用环境执行 |

## 5. 验收结论模板

```text
环境：
数据规模：
执行人：
执行时间：

对话首屏 P95：
预览接口 P95：
文档列表错误数：
搜索接口错误数：

结论：通过 / 不通过
遗留问题：
```
