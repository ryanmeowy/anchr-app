# DEMO 分支说明

这个分支只服务公开演示部署，**永不合并回 `main`**。

- 需求：[DEMO-PRD.md](./DEMO-PRD.md)
- 技术方案：[DEMO-TECH-DESIGN.md](./DEMO-TECH-DESIGN.md)

---

## 1. 新增文件（8 个）

| 文件 | 职责 |
| --- | --- |
| `common/config/DemoProperties.java` | 全部演示参数，**每个都有默认值** |
| `common/config/DemoConfig.java` | 注册 `@ConfigurationProperties` |
| `common/constant/DemoConstant.java` | Redis key 前缀、cookie 名 |
| `common/infrastructure/DemoQuota.java` | 计费注解 |
| `common/infrastructure/DemoQuotaInterceptor.java` | 扣减与 429 |
| `common/infrastructure/DemoKillSwitch.java` | Redis 熔断，5 秒内生效 |
| `auth/application/impl/DemoTokenService.java` | 发券、幂等、冷却、每日上限 |
| `auth/interfaces/rest/DemoAuthController.java` | `/api/v1/auth/demo*` |
| `integration/security/TurnstileVerifier.java` | Turnstile 服务端校验 |

## 2. 改动的共享文件（9 个）

| 文件 | 改动 | 为什么必须改 |
| --- | --- | --- |
| `WebConfig` | 注册第二个拦截器 | 配额必须在认证之后执行 |
| `AccessTokenInterceptor` | 3 行：从 token 读 `userId` | 访客身份的唯一来源 |
| `ApiError` | 末尾加 5 个枚举 | 前端要区分四种状态 |
| `ConversationSessionUseCase` | 2 处 `SINGLE_USER_ID` → 上下文 | 会话按访客隔离 |
| `ConversationMessageUseCase` | 1 处 → 上下文 | 任务记录携带 owner |
| `ConversationMessageOrchestrator` | 1 处 → 上下文 | Agent run 归属 |
| `AgentRunActivityService` | 1 处 → 上下文 | 可恢复 run 按访客过滤 |
| `ConversationConstant` | 删除 `SINGLE_USER_ID` | 不再需要常量 |
| `IngestionCapabilityService` | 注入 `DemoProperties`，`maxFilesPerBatch` 可变 | 演示限 1 个文件 |
| `IngestionTaskCreateUseCase` | `validateUploadLimits` 加数量校验 | **原来只声明不校验** |
| `ConversationController` / `SearchController` / `KnowledgeBaseIngestionController` | 各加 `@DemoQuota`（共 6 处） | 计费入口 |
| `application.yaml` | `app.demo.*` 一段 | 配置来源 |

---

## 3. 每次同步主干后必须检查的 4 件事

```bash
git merge origin/main
```

合并后逐项验证（`DEMO.md` 的核心价值就在这一节）：

1. **领券仍可用**：`POST /api/v1/auth/demo` 返回 token，重复调用返回同一张（cookie 幂等）
2. **计费仍准确**：6 个端点扣 1 次，其余端点不扣
3. **熔断仍生效**：切换后 5 秒内计费端点 503、`/actuator/health` 仍 200
4. **上传仍限 1 个**：`curl` 传 2 个文件返回 400

> 重点看 `AccessTokenInterceptor`。主干历史上改过 6 次，如果它重构了上下文写入方式，
> `userId` 那 3 行要重新接上。

---

## 4. worker 线程铁律

`AgentTaskProcessor` 和 `IngestionTaskProcessorImpl` 运行在 `@Scheduled` 线程，
`UserContextHolder.get()` 在那里返回 `system`。

**规则**：

- 创建任务时（请求线程）把 `userId` 写进任务记录
- worker 处理时只读 `task.getUserId()`

**不要**在 worker 里把 `SINGLE_USER_ID` 机械替换成 `UserContextHolder.get().userId()`，
否则演示数据会静默归到 `system`。

---

## 5. 演示环境参数

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `APP_DEMO_ENABLED` | `false` | 总开关 |
| `APP_DEMO_TOKEN_TTL_HOURS` | `5` | token 有效期 |
| `APP_DEMO_TOKEN_QUOTA` | `8` | 单券可计费次数 |
| `APP_DEMO_DAILY_OPERATION_LIMIT` | `80` | 全站每日可计费操作数 |
| `APP_DEMO_DAILY_TOKEN_LIMIT` | `50` | 全站每日发券数 |
| `APP_DEMO_ISSUE_COOLDOWN_MINUTES` | `10` | 同一 cookie 发券冷却 |
| `APP_DEMO_UPLOAD_MAX_FILES` | `1` | 单次上传文件数 |
| `APP_DEMO_TURNSTILE_ENABLED` | `true` | Turnstile 开关 |
| `APP_DEMO_TURNSTILE_SITE_KEY` | 空 | Cloudflare sitekey |
| `APP_DEMO_TURNSTILE_SECRET_KEY` | 空 | Cloudflare secret |

演示环境另需：

```bash
SPRING_DATA_REDIS_DATABASE=4
ANCHR_SEGMENT_INDEX=demo_kb_segment     # 不要用 kb_segment_demo，会被生产的 kb_segment_* 通配符命中
MYSQL_URL=jdbc:mysql://.../anchr_demo?...
APP_ENCRYPT_KEY=<新生成>
APP_ADMIN_SECRET=<新生成>
```

---

## 6. 应急操作

### 6.1 一键熔断（5 秒内生效）

```bash
curl -X POST -H "X-Admin-Secret: $APP_ADMIN_SECRET" \
  "https://<demo-api>/api/v1/auth/demo/kill-switch?on=true"
```

熔断后：计费端点与发券返回 503；`/actuator/**` 与只读端点仍放行。

恢复：

```bash
curl -X POST -H "X-Admin-Secret: $APP_ADMIN_SECRET" \
  "https://<demo-api>/api/v1/auth/demo/kill-switch?on=false"
```

### 6.2 只清配额（方案 A）

Redis 禁用了 `FLUSHDB`，只能按前缀删：

```bash
redis-cli -n 4 --scan --pattern 'demo:quota:*' | xargs -r redis-cli -n 4 del
```

### 6.3 清会话与活动（方案 B）

```sql
-- agent_run and agent_step carry no user_id; reach them through the session they belong to.
-- Delete children before parents so no statement depends on rows removed earlier in the batch.
DELETE FROM agent_step  WHERE run_id IN (SELECT run_id FROM agent_run
                                         WHERE session_id IN (SELECT session_id
                                                              FROM conversation_session
                                                              WHERE user_id LIKE 'demo_%'));
DELETE FROM agent_run   WHERE session_id IN (SELECT session_id FROM conversation_session
                                             WHERE user_id LIKE 'demo_%');
DELETE FROM agent_task  WHERE user_id LIKE 'demo_%';
DELETE FROM conversation_turn WHERE session_id IN (SELECT session_id FROM conversation_session
                                                   WHERE user_id LIKE 'demo_%');
DELETE FROM activity_event WHERE user_id LIKE 'demo_%';
DELETE FROM conversation_session WHERE user_id LIKE 'demo_%';
```

知识库与文档保留。

### 6.4 完整重建（方案 C，仅在更新演示内容时）

清 `anchr_demo` 库 → 删 `demo_kb_segment_*` 索引 → 清演示 OSS 前缀 → 重放 seed。

---

## 7. 已知取舍

| 项 | 取舍 | 何时升级 |
| --- | --- | --- |
| 配额计数非原子 | `INCR` 后比较、失败 `DECR` 回滚 | 并发下最多多放行 1–2 次；额度敏感时改 Lua |
| 上传按文件计费实际按请求算 | 因 `upload-max-files=1` 两者等价 | 放开文件数上限时必须读 body 计数 |
| 熔断读取每进程缓存 5 秒 | 换掉每请求一次 Redis 往返 | 已是延迟与开销的折中 |
| 配额是次数限制，不是成本控制 | Agent 与普通提问同价 1 次 | 成本靠 `DAILY_OPERATION_LIMIT` 兜底 |
| Redis `allkeys-lru` | 配额 key 可能被驱逐后短暂失效 | 彻底解决需独立 Redis 实例改 `volatile-lru` |
