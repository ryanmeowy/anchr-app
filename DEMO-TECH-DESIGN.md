# Anchr 演示环境技术方案

> 配套文档：[DEMO-PRD.md](./DEMO-PRD.md)（v6，10 项决策已确认）
> 适用范围：`anchr-app` / `anchr-web` 的 `demo` 分支 + 演示环境部署
> 本文只讲"怎么做"，不含需求论证

---

## 1. 分支与仓库策略

### 1.1 分支

```
anchr-app:  demo   ← 从 main (5d74328) 切出，永不合并回 main
anchr-web:  demo   ← 从 main (39be9e5) 切出，永不合并回 main
```

**永不合并**的直接后果：不需要任何 `@ConditionalOnProperty` 开关。演示逻辑直接写死，代码更简单。

### 1.2 与主干同步

| 场景 | 操作 |
| --- | --- |
| 主干有产品改进要带到演示 | `git merge origin/main`，重点解 `AccessTokenInterceptor`、`ApiError`、三个 Controller 的冲突 |
| 同步后必做 | 按 `DEMO.md` 清单逐项验证：领券、计费、熔断、上传限制四件事还生效 |

**为什么冲突量小**：本次只改 5 个主干文件，其中 4 个是"加几行"（注解、枚举值、配置项），1 个是 3 行赋值改动。其余全部是新建文件。

### 1.3 文件清单总览

| 类型 | 文件 | 行数估算 |
| --- | --- | --- |
| 新建 | `common/config/DemoProperties.java` | ~50 |
| 新建 | `auth/interfaces/rest/DemoAuthController.java` | ~90 |
| 新建 | `common/infrastructure/DemoQuota.java`（注解） | ~15 |
| 新建 | `common/infrastructure/DemoQuotaInterceptor.java` | ~110 |
| 新建 | `integration/security/AliyunCaptchaVerifier.java` | ~100 |
| 新建 | `integration/security/CaptchaSceneIdEncryptor.java` | ~80 |
| 改主干 | `common/infrastructure/AccessTokenInterceptor.java` | +3 |
| 改主干 | `common/config/WebConfig.java` | +3 |
| 改主干 | `common/exception/ApiError.java` | +5 |
| 改主干 | `conversation/application/constant/ConversationConstant.java` | ±0（改用上下文） |
| 改主干 | conversation 下 4 个类 | 各 ±1~2 |
| 改主干 | `ingestion/.../IngestionTaskCreateUseCase.java` | +6 |
| 改主干 | 3 个 Controller | 各 +1~2 |
| 改主干 | `resources/application.yaml` | +7 |
| 新建 | `DEMO.md` | 文档 |

---

## 2. 配置

### 2.1 `application.yaml` 追加

```yaml
app:
  demo:
    enabled: ${APP_DEMO_ENABLED:false}
    token-ttl-hours: ${APP_DEMO_TOKEN_TTL_HOURS:2}
    token-quota: ${APP_DEMO_TOKEN_QUOTA:10}
    daily-operation-limit: ${APP_DEMO_DAILY_OPERATION_LIMIT:80}
    daily-token-limit: ${APP_DEMO_DAILY_TOKEN_LIMIT:50}
    issue-cooldown-minutes: ${APP_DEMO_ISSUE_COOLDOWN_MINUTES:10}
    upload-max-files: ${APP_DEMO_UPLOAD_MAX_FILES:1}
    captcha-enabled: ${APP_DEMO_CAPTCHA_ENABLED:true}
    captcha-scene-id: ${APP_DEMO_CAPTCHA_SCENE_ID:}
    captcha-prefix: ${APP_DEMO_CAPTCHA_PREFIX:}
    captcha-region: ${APP_DEMO_CAPTCHA_REGION:cn-shanghai}
    captcha-access-key-id: ${APP_DEMO_CAPTCHA_ACCESS_KEY_ID:}
    captcha-access-key-secret: ${APP_DEMO_CAPTCHA_ACCESS_KEY_SECRET:}
    captcha-ekey: ${APP_DEMO_CAPTCHA_EKEY:}
    captcha-encrypt-scene-id: ${APP_DEMO_CAPTCHA_ENCRYPT_SCENE_ID:false}
    captcha-scene-id-ttl-seconds: ${APP_DEMO_CAPTCHA_SCENE_ID_TTL_SECONDS:3600}
```

> **所有值必须带默认值**。生产环境的 `application.yaml` 里 `app.security.admin-secret` 等都没有默认值（缺失即启动失败），若照抄这种写法会导致生产环境拉取本次代码后起不来。

### 2.2 `DemoProperties` 要点

- `@ConfigurationProperties(prefix = "app.demo")`
- 用 record 或带 `@Validated` 的类，**单构造器**（满足 `SpringBeanConstructorContractTest`）
- 构造器内做跨字段校验，**配置写错则启动失败**：
  - `tokenQuota > 0`、`dailyOperationLimit > 0`、`dailyTokenLimit > 0`
  - `uploadMaxFiles >= 1`
  - `enabled == true` 且 `captchaEnabled == true` 时：`captchaSceneId` 不能为空
  - 加密模式开启时：`captchaEkey` 不能为空

### 2.3 演示环境 `.env` 差异

```bash
APP_DEMO_ENABLED=true
APP_DEMO_TOKEN_QUOTA=10
APP_DEMO_DAILY_OPERATION_LIMIT=80
APP_DEMO_DAILY_TOKEN_LIMIT=50
APP_DEMO_UPLOAD_MAX_FILES=1
APP_DEMO_CAPTCHA_SCENE_ID=<控制台场景 ID>
APP_DEMO_CAPTCHA_PREFIX=<控制台 prefix>
APP_DEMO_CAPTCHA_ACCESS_KEY_ID=<RAM 凭据>
APP_DEMO_CAPTCHA_ACCESS_KEY_SECRET=<RAM 凭据>

SPRING_DATA_REDIS_DATABASE=4
ANCHR_SEGMENT_INDEX=demo_kb_segment
MYSQL_URL=jdbc:mysql://...:3306/anchr_demo?...
APP_ENCRYPT_KEY=<新生成>
APP_ADMIN_SECRET=<新生成>
```

---

## 3. 领券接口

### 3.1 端点

```
POST /api/v1/auth/demo
Content-Type: application/json

{ "captchaVerifyParam": "xxx" }
```

标注 `@PermitAll`（`ControllerAuthorizationContractTest` 强制要求显式声明）。

### 3.2 响应

```json
{ "code": 200, "data": { "token": "...", "role": "USER", "userId": "demo_a1b2c3d4", "expiresAt": 1792900000000 } }
```

### 3.3 处理流程

```
1. demo.enabled == false            → DEMO_DISABLED
2. kill switch 打开                 → DEMO_DISABLED
3. 请求带有效 cookie                → 直接返回该 cookie 绑定的 token（幂等，不消耗额度）
4. issue cooldown (同 cookie)       → DEMO_ISSUE_TOO_FREQUENT
5. 人机校验失败/超时                → DEMO_VERIFICATION_FAILED
6. daily issued limit 已达          → DEMO_DAILY_LIMIT_REACHED
7. 生成 token + userId，写 Redis，下发 cookie
```

### 3.4 Token 存储结构

复用现有前缀 `sys:config:auth-token:`，值从 `{role, createdAt}` 扩展为：

```json
{ "role": "USER", "userId": "demo_a1b2c3d4", "createdAt": 1792880000000, "demo": true }
```

- TTL = `token-ttl-hours`（默认 2 小时），**独立常量，不碰现有 1 小时 TTL**
- `demo: true` 标记让配额拦截器只对演示凭证生效

### 3.5 Cookie

| 属性 | 值 |
| --- | --- |
| 名称 | `anchr_demo_id` |
| 内容 | 随机 24 字节（Base64URL），**不是 token 本身** |
| `HttpOnly` | 是 |
| `Secure` | 演示环境为 HTTPS，是 |
| `SameSite` | `Lax` |
| `Path` | `/` |
| `Max-Age` | 与 token TTL 一致（默认 2 小时） |

**为什么 cookie 里存的是独立 id 而不是 token**：token 需要被前端 JS 读取（现有 `X-Access-Token` 机制不变），若 cookie 里也是 token，等于绕过 `HttpOnly` 的意义。cookie 只用于"这台浏览器已经领过券"的幂等判定。

### 3.6 幂等键

```
demo:issue:token:{cookieId}   → 已下发的 token，TTL = token TTL
demo:issue:cooldown:{cookieId} → 冷却标记，TTL = 10 分钟
demo:quota:daily:issued:{yyyy-MM-dd} → 当日已发券数，TTL 到次日 00:05
```

---

## 4. 人机校验接入（阿里云验证码 2.0）

> 早期方案用 Cloudflare Turnstile，因脚本托管在境外、国内访客无代理时不可靠而替换。

### 4.1 服务端校验

```
POST https://captcha.cn-shanghai.aliyuncs.com/
Action=VerifyIntelligentCaptcha
SceneId=<场景 ID>&CaptchaVerifyParam=<前端回传的串>
```

响应判定：`Success == true` 且 `Result.VerifyResult == true` 才继续；两层都要满足，因为传输成功不等于验证通过。

### 4.2 实现要点

- 复用 `aliyun-java-sdk-captcha`（与 OSS/STS 同一套 SDK 体系）
- 必须显式 `request.setEndpoint("captcha.cn-shanghai.aliyuncs.com")`：SDK 内置的端点表里没有 captcha 条目
- 失败（超时 / 非 200 / `VerifyResult == false`）→ `DEMO_VERIFICATION_FAILED`
- **fail closed**：验证服务不可达时拒绝发券
- 日志记录 `Code` 与 `VerifyCode`，便于排查

### 4.3 本地开发

`app.demo.captcha.enabled=false` 时跳过校验，启动日志 WARN 输出一行：

```
WARN  CAPTCHA verification is DISABLED; not for production use.
```

### 4.4 加密模式（可选）

控制台开启加密模式后，服务端必须下发加密串而非明文场景 ID：

```
plaintext = sceneId + "&" + epochSeconds + "&" + ttlSeconds
key       = Base64.decode(ekey)              # 32 字节
cipher    = AES-256-CBC / PKCS5
output    = Base64(iv ‖ ciphertext)          # iv 每次随机
```

前端初始化时 **同时**传 `SceneId` 和 `EncryptedSceneId`：

- 只传 `EncryptedSceneId` → SDK 无法计算签名，返回 401
- 只传 `SceneId` → 服务端无法解密，返回 F022

### 4.5 前端

- 引入 `o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js`，popup 模式
- `initAliyunCaptcha` 的 `element` / `button` 必须传 **以 `#` 开头的选择器**，SDK 只认这种形式
- 初始化前先设 `window.AliyunCaptchaConfig = { region, prefix }`，顺序不能反
- 脚本加载失败必须移除 `<script>` 标签，否则重试会挂在已失败的标签上永不返回
- 关闭弹窗时需手动清理 SDK 插入到 `<body>` 的全屏遮罩，否则访客被困

---

## 5. 配额拦截

### 5.0 核对结论：3 个 Controller 不做构造器改动

标注 `@DemoQuota` 只是加注解，**不新增依赖**，所以 3 个 Controller 的构造器签名不变。这一点很重要——它们被测试直接构造的次数分别是：

| Controller | 测试中 `new` 的次数 |
| --- | --- |
| `KnowledgeBaseIngestionController` | 10 |
| `ConversationController` | 2 |
| `SearchController` | 1 |

**13 处测试构造全部零改动。**

### 5.1 架构决策：独立拦截器，不改 `AccessTokenInterceptor` 的构造器

`AccessTokenInterceptorTest` 用 4 参数构造器直接 `new` 该拦截器。若往构造函数加依赖，该测试编译失败，需要连带修改。

**方案**：配额逻辑放入**独立的 `DemoQuotaInterceptor`**，在 `WebConfig` 中注册在 `AccessTokenInterceptor` **之后**。

```java
registry.addInterceptor(accessTokenInterceptor).addPathPatterns("/api/**");
registry.addInterceptor(demoQuotaInterceptor).addPathPatterns("/api/**");
```

好处：

- `AccessTokenInterceptor` 只改 3 行（userId），构造器不变，**现有测试零改动**
- 配额逻辑独立可测，不必构造整个认证栈

### 5.2 计费注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DemoQuota {}
```

**不带参数**——计数恒为 1（Agent 与普通提问同价，见 PRD F2.6），所以不需要 `cost` 字段。

### 5.3 标注位置（8 处）

| Controller | 方法 | 完整路径 |
| --- | --- | --- |
| `ConversationController` | `createMessage` | `POST /api/v1/conversations/{sessionId}/messages` |
| `ConversationController` | `createMessageStream` | `POST /api/v1/conversations/{sessionId}/messages/stream` |
| `SearchController` | `searchKb` | `POST /api/v1/search/kb` |
| `KnowledgeBaseIngestionController` | `createTask` | `POST /api/v1/kbs/{kbId}/ingestion-tasks` |
| `KnowledgeBaseIngestionController` | `reparse` | `POST /api/v1/kbs/{kbId}/documents/{assetId}/reparse` |
| `KnowledgeBaseIngestionController` | `reembed` | `POST /api/v1/kbs/{kbId}/documents/{assetId}/reembed` |

**不标注**：`POST /api/v1/conversations`（建会话）、所有 GET、retry-failed / retry item。

> 建会话不计费的理由：前端首次提问前会自动建会话（[ask-premium-page.tsx:2660](/Users/ryan/code/anchr-web/src/features/ask/ask-premium-page.tsx)），若计费则每次对话多扣 1 次；且建会话不产生任何模型调用。

**上传按文件计费**：`createTask` 标 `@DemoQuota`，单价 1；文件数通过 `uploadMaxFiles` 限制为 1，所以"按文件计费"与"每请求 1 次"在此配置下等价。若将来放开文件数上限，需要给拦截器增加"读 body 取 items 数量"的能力（标注 `shortcut:`）。

### 5.4 拦截流程

```java
preHandle():
    if (!demoProperties.enabled()) return true;

    boolean billable = findMergedAnnotation(HandlerMethod, DemoQuota.class) != null;
    if (!billable) return true;                       // 不计费端点完全不碰 Redis

    if (killSwitch.on())            → 503 DEMO_DISABLED
    if (UserContextHolder.get().role() == ANONYMOUS) return true;  // @PermitAll 放行

    String tokenHash = UserContextHolder.get().accessTokenHash();
    if (tokenHash == null) return true;               // 非 token 认证路径，不做配额

    // 1) 单券计数
    long used = redis.increment(KEY_TOKEN + tokenHash);
    if (used == 1) redis.expire(KEY_TOKEN + tokenHash, TOKEN_COUNTER_TTL);
    if (used > quota) { redis.decrement(...); → 429 DEMO_QUOTA_EXHAUSTED }

    // 2) 每日总量
    long daily = redis.increment(KEY_DAILY + today);
    if (daily == 1) redis.expire(KEY_DAILY + today, ttlToNextDay);
    if (daily > dailyLimit) {
        redis.decrement(KEY_TOKEN + tokenHash);       // 回滚
        redis.decrement(KEY_DAILY + today);
        → 429 DEMO_DAILY_LIMIT_REACHED
    }
    return true;
```

**已知取舍**（代码里必须写 `shortcut:` 注释）：

```
shortcut: 计数非原子（INCR 成功后才比较、失败再 DECR），并发下最多多放行 1-2 次。
演示额度内可忽略；若额度变得敏感，改为 Lua 脚本原子扣减。
```

**计数器 TTL** 必须**长于 token TTL**（默认 3 小时 vs 2 小时），否则 token 还在、计数器先过期，额度被重置。

### 5.5 Redis key 设计

| Key | 类型 | TTL | 说明 |
| --- | --- | --- | --- |
| `demo:quota:token:{tokenHash}` | string | 6h | 单券已用次数 |
| `demo:quota:daily:ops:{yyyy-MM-dd}` | string | 至次日 00:05 | 当日总操作数 |
| `demo:quota:daily:issued:{yyyy-MM-dd}` | string | 至次日 00:05 | 当日已发券数 |
| `demo:issue:token:{cookieId}` | string | 5h | cookie → token 映射（幂等） |
| `demo:issue:cooldown:{cookieId}` | string | 10min | 发券冷却 |
| `demo:killswitch` | string | 无 | `1` = 熔断 |

> 全部带 TTL，因为演示环境共用生产 Redis，其策略为 `allkeys-lru`（key 可能被驱逐）。被驱逐后限额会短暂失效，靠每日总量在业务层兜底。

---

## 6. 熔断开关

### 6.1 状态存储与读取

- 状态存 Redis `demo:killswitch`
- **进程内缓存 5 秒**，避免每个请求都读 Redis（PRD N20）

```java
private volatile boolean cached = false;
private volatile long cachedUntil = 0L;

boolean on() {
    long now = System.currentTimeMillis();
    if (now < cachedUntil) return cached;
    try {
        cached = "1".equals(redis.opsForValue().get(KEY));
    } catch (RuntimeException e) {
        // Redis 不可达时 fail open：此时配额本就不生效，再拒绝请求会形成双故障
        cached = false;
        log.warn("Kill switch read failed, falling back to OPEN: {}", e.getMessage());
    }
    cachedUntil = now + 5000;
    return cached;
}
```

### 6.2 管理端点

```
POST /api/v1/auth/demo/kill-switch?on=true
Header: X-Admin-Secret: <APP_ADMIN_SECRET>
```

复用 `AuthController` 现成的 `constantTimeEquals` 比较模式。切换后写日志（操作人、时间、目标状态）。

**运维命令**（PRD N22 要求 5 秒内可执行）：

```bash
curl -X POST -H "X-Admin-Secret: $APP_ADMIN_SECRET" \
  "https://demo-api.example.com/api/v1/auth/demo/kill-switch?on=true"
```

### 6.3 熔断范围

| 路径 | 熔断时 |
| --- | --- |
| `/actuator/**`、`/api/v1/health` | 放行（不受 `@DemoQuota` 标注，天然放行） |
| 发券接口 | 503 `DEMO_DISABLED` |
| 8 个计费端点 | 503 `DEMO_DISABLED` |
| 只读端点 | 放行 |

**熔断只在 `DemoQuotaInterceptor` 与 `DemoAuthController` 内实现**，不影响任何其他路径。

---

## 7. 用户标识改造（`SINGLE_USER_ID` → 真实 userId）

### 7.1 现状

`SINGLE_USER_ID = "single_user"`，被 4 个类引用（共 6 处），`RequestUserContext.DEFAULT_USER_ID = "system"` 被拦截器硬编码使用。

### 7.2 改造

**拦截器（3 行）**：

```java
String userId = (String) tokenData.getOrDefault("userId", "");
UserContextHolder.set(new RequestUserContext(
        StringUtils.hasText(userId) ? userId : RequestUserContext.DEFAULT_USER_ID,
        role,
        tokenHash(clientToken)
));
```

**4 个消费点**：把 `SINGLE_USER_ID` 换成 `UserContextHolder.get().userId()`。

| 类 | 行 | 是否在请求线程 |
| --- | --- | --- |
| `ConversationSessionUseCase` | 48、66 | 是 |
| `ConversationMessageUseCase` | 312 | 是 |
| `ConversationMessageOrchestrator` | 87 | 是（SSE 线程，上下文已传播） |
| `AgentRunActivityService` | 69 | 是（请求驱动） |

> **SSE 上下文传播已验证**：[ConversationMessageStreamAdapter.java:92](src/main/java/com/anchr/core/conversation/interfaces/rest/ConversationMessageStreamAdapter.java) 在 `streamExecutor` 中显式 `UserContextHolder.set(context)`，所以流式路径能拿到正确 userId。

### 7.3 关键约束：worker 线程禁止调用 `UserContextHolder.get()`

`AgentTaskProcessor`（`@Scheduled`）与 `IngestionTaskProcessorImpl`（`@Scheduled`）运行在无请求的线程里，`UserContextHolder.get()` 会返回 `systemDefault()`（`system`）。

**规则**：

- 创建任务时（请求线程）把 userId 写进任务记录（`ConversationMessageUseCase:312` 已经在做）
- worker 处理时**只从任务记录读** `task.getUserId()`，不读上下文

> 这条要写进 `DEMO.md` 的检查清单。将来若有人把 `SINGLE_USER_ID` 机械替换成 `UserContextHolder.get().userId()`，worker 路径会静默变成 `system`。

### 7.4 兼容性

非演示 token（生产签发的）不含 `userId` → 落回 `system`，**行为与改造前完全一致**。所以在 `demo` 分支上，这条改动对生产路径是零影响。

---

## 8. 上传数量限制

### 8.1 服务端校验（必补）

当前 `MAX_FILES_PER_BATCH` **只用于 capabilities 响应，无任何校验**。在 `IngestionTaskCreateUseCase.validateUploadLimits` 内追加：

```java
int maxFiles = demoProperties.enabled()
        ? demoProperties.uploadMaxFiles()
        : IngestionConstant.MAX_FILES_PER_BATCH;
if (request.items().size() > maxFiles) {
    throw new BusinessException(ApiError.INVALID_REQUEST,
            "Too many files in one request. Limit: " + maxFiles);
}
```

**注意**：`validateUploadLimits` 接收 `NormalizedCreateRequest`，需确认其 `items()` 可访问（已是 `List<IngestionCreateItemCommand>`）。

### 8.2 能力声明同步

`IngestionCapabilityService.getCapabilities()` 的 `maxFilesPerBatch` 也要按 `demo.enabled` 返回 1，否则前端 UI 会显示"单次最多 20 个"，与实际不符。

### 8.3 必须连带修改的测试（4 处）

这两个类都被测试**直接 `new`**，加构造参数会导致编译失败，`demo` 分支里必须同步改：

| 测试文件 | 位置 | 改法 |
| --- | --- | --- |
| `IngestionApplicationServiceImplTest` | :93 `new IngestionCapabilityService()` | 传 `new DemoProperties(...)`（关闭态） |
| `IngestionApplicationServiceImplTest` | :88 `new IngestionTaskCreateUseCase(...)` | 末尾补一个参数 |
| `IngestionCapabilityServiceTest` | :10 字段初始化 | 同上 |
| `IngestionCapabilityDTOTest` | :14 | 同上 |

> `SpringBeanConstructorContractTest` 要求每个 Bean **恰好一个构造器**，因此不能用无参构造器兜底，只能改测试。
>
> 测试里传入的 `DemoProperties` 用**全部默认值（`enabled=false`）**，这样 4 个既有断言（`MAX_FILE_SIZE_BYTES` 等）保持不变。

---

## 9. 错误码

在 `ApiError` 末尾追加：

| 枚举名 | code | 消息 |
| --- | --- | --- |
| `DEMO_DISABLED` | 503 | 演示环境暂时关闭，请稍后再试。 |
| `DEMO_QUOTA_EXHAUSTED` | 429 | 体验次数已用完，可重新领取。 |
| `DEMO_DAILY_LIMIT_REACHED` | 429 | 今日演示额度已用完，明天再来。 |
| `DEMO_VERIFICATION_FAILED` | 403 | 无法完成人机校验，请关闭拦截该站点的浏览器扩展后重试。 |
| `DEMO_ISSUE_TOO_FREQUENT` | 429 | 领取过于频繁，请稍后再试。 |

**前端靠 `errorCode` 字符串区分四种状态，不靠 HTTP 状态码**（三个都是 429）。

`ResultHttpStatusAdvice` 与 `GlobalExceptionHandler.resolveStatus` 都用 `HttpStatus.resolve(error.getCode())`，429 能正确映射，无需改动。

---

## 10. 前端改造（`anchr-web` 的 `demo` 分支）

### 10.1 删除

| 项 | 位置 |
| --- | --- |
| 硬编码 GUEST token | [api-client.ts:61](/Users/ryan/code/anchr-web/src/lib/api-client.ts) `DEFAULT_GUEST_ACCESS_TOKEN` |
| 设置页"粘贴令牌"入口 | `settings-management-page.tsx`、`settings-premium-page.tsx` |

> 删 `DEFAULT_GUEST_ACCESS_TOKEN` 后 `getAccessToken()` 在无 token 时返回空 → 请求不带 `X-Access-Token` → 401 → 触发领券流程。这条链路要让 `isAuthenticationError` 能识别。

### 10.2 新增

**领券流程**（放在 workspace layout 或独立 gate 组件）：

```
无 token → 显示「开始体验」按钮
         → 点击 → 渲染阿里云滑块 → 取 captchaVerifyParam
         → POST /auth/demo
         → 成功后 saveAccessToken(token, "USER")
         → 进入工作区
```

**4 种状态**：

| 状态 | 判定 | 表现 |
| --- | --- | --- |
| 无凭证 | 无 token | 显示按钮 |
| 有余量 | 有 token | 直接进入 |
| 配额用尽 | `errorCode == DEMO_QUOTA_EXHAUSTED` | 提示 + 「重新领取」，清掉旧 token |
| 全站用尽 | `errorCode == DEMO_DAILY_LIMIT_REACHED` | 提示 + 按钮置灰 |

**Agent 默认开启**（[ask-premium-page.tsx:618](/Users/ryan/code/anchr-web/src/features/ask/ask-premium-page.tsx)）：

```diff
- setAgentEnabled(window.localStorage.getItem(ASK_AGENT_ENABLED_KEY) === "1");
+ setAgentEnabled(window.localStorage.getItem(ASK_AGENT_ENABLED_KEY) !== "0");
```

### 10.3 人机校验脚本失败的兜底

**这是全套方案里最容易让演示翻车的点**（广告拦截器会拦 `o.alicdn.com`）。

要求：

- 脚本加载失败时**不能静默卡住**
- 监听脚本 `onerror` 与 SDK 的 `onError` 回调
- 失败时必须 `script.remove()`，否则重试会给一个已经失败、再也不会触发事件的标签挂监听，Promise 永不 settle
- 关闭弹窗时要清理 SDK 插到 `<body>` 的全屏遮罩（`.aliyunCaptcha-top`），否则访客被困在无法点击的页面上
- 提交流程中若 `DEMO_VERIFICATION_FAILED` → 提示重试（`captchaVerifyParam` 单次有效，不能重放）

### 10.4 已踩过的坑

| 现象 | 原因 |
| --- | --- |
| `SDK.EndpointResolvingError` | SDK 内置端点表没有 captcha，需显式 `setEndpoint` |
| `region参数传入值不合法` | 前端传 `cn`，不是 `cn-shanghai`（与服务端 region 是两套命名） |
| `button参数传入值不合法` | `element`/`button` 必须以 `#` 开头 |
| `INIT_FAIL`，请求打到 `undefined.captcha-open...` | `AliyunCaptchaConfig.prefix` 没在任何请求前设置 |
| `401 Specified signature is not matched` | 只传 `EncryptedSceneId` 没传 `SceneId` |
| `F022` 全部拒绝 | 控制台开了加密模式，但服务端没下发加密串（或反之） |

---

## 11. 联调与部署顺序

```
1. 云控制台：建 anchr_demo 库、演示 OSS bucket、RAM 角色、验证码场景（scene id / prefix / ekey）
2. Docling：ANCHR_DOCLING_ALLOWED_UPLOAD_TARGETS 加演示 bucket → 重启 Docling
   └─ 验证：传一张有图的 PDF，确认解析结果里图片被提取（不是仅 warnings）
3. 切 demo 分支，实现后端（DemoProperties → DemoQuotaInterceptor → DemoAuthController → AliyunCaptcha → 上传校验 → userId 改造）
4. 部署演示 App，确认连的是 anchr_demo / db 4 / demo_kb_segment
5. 前端 demo 分支：删硬编码 token、加领券 gate、Agent 默认开
6. 导入预置内容（seed）
7. 端到端验收（见 PRD §9）
```

---

## 12. 测试方案

### 12.1 后端单测（新增）

| 测试 | 覆盖 |
| --- | --- |
| `DemoPropertiesTest` | 跨字段校验：非法值启动失败 |
| `DemoQuotaInterceptorTest` | 未标注端点不碰 Redis；单券超限 429；每日超限 429 且回滚单券计数 |
| `AliyunCaptchaVerifierTest` | 通过 / 不通过 / 异常 三种分支 |
| `CaptchaSceneIdEncryptorTest` | 加解密互逆、TTL 边界、ekey 长度校验 |
| `DemoAuthControllerTest` | cookie 幂等、冷却、发券上限、熔断 |

**注意**：`AccessTokenInterceptorTest` 内的 `StubStringRedisTemplate` **只实现了 `get`**。新测试需要自己的 stub 支持 `increment` / `decrement` / `expire`，不要复用该 stub。

### 12.2 必须做的负向测试

| 用例 | 期望 |
| --- | --- |
| curl 传 2 个文件建上传任务 | 400，不是 200 |
| 不带 captchaVerifyParam 领券 | 403 |
| 服务端校验失败时拒绝发券 | 403，且不消耗每日签发额度 |
| 清 localStorage 后重复领券 | 取回同一张（cookie 幂等） |
| 清 cookie 后重复领券 | 冷却期内 429 |
| 熔断后调计费端点 | 503；`/actuator/health` 仍 200 |
| Redis 停掉后调计费端点 | 不报 500（fail open） |

### 12.3 人工验收（PRD §9）

必须用**装了 uBlock Origin 的浏览器**走一遍领券流程。这是唯一无法用自动化覆盖、却最可能让演示失败的一环。

---

## 13. `DEMO.md` 应包含的内容

1. **改动清单**：新建 5 个文件、改主干 5 个文件，逐条说明为什么必须改主干
2. **同步主干后的检查项**（4 条）：
   - 领券仍能成功且 cookie 幂等
   - 8 个端点仍计费、其余不计费
   - 熔断仍 5 秒内生效
   - 上传仍限 1 个文件
3. **worker 线程规则**：禁止在 `@Scheduled` 线程调用 `UserContextHolder.get()`
4. **演示参数一览**：8 个环境变量及含义
5. **应急操作**：熔断命令、按前缀清配额、重置流程 A/B

---

## 14. 既有测试影响汇总

改构造器或加依赖会破编译，`demo` 分支里需同步修改的测试只有 4 处（见 §8.3）。其余全部不受影响：

| 测试 | 影响 |
| --- | --- |
| `AccessTokenInterceptorTest` | **零改动**（未加构造参数；但也意味着它覆盖不到配额逻辑） |
| `AuthControllerHttpContractTest` | **零改动**（发票端点在新建的 `DemoAuthController`） |
| `ControllerAuthorizationContractTest` | **新端点必须标 `@PermitAll`**，否则该测试会红——这是它应有的保护 |
| `SpringBeanConstructorContractTest` | 新建 5 个 Bean 必须**单构造器** |
| `IngestionCapabilityServiceTest` / `IngestionCapabilityDTOTest` / `IngestionApplicationServiceImplTest` | 按 §8.3 改构造调用 |

> `AuthControllerHttpContractTest` 之所以零改动：它用 3 参数构造器 `new AuthController(redis, acl, mapper)`。**发票端点必须放在新类 `DemoAuthController` 里**，不要塞进 `AuthController`，否则该测试编译失败。

## 15. 已知取舍与遗留

| 项 | 取舍 | 升级条件 |
| --- | --- | --- |
| 配额计数非原子 | `INCR` 后比较、失败 `DECR` 回滚 | 并发下最多多放行 1–2 次；额度敏感时改 Lua |
| 上传按文件计费实际按请求算 | 因 `uploadMaxFiles=1`，两者等价 | 放开文件数上限时必须读 body 计数 |
| 每请求 1 次 Redis 读（熔断） | 进程内缓存 5 秒 | 已是延迟与开销的折中 |
| 凭证过期自动换券 | 前端在收到 `DEMO_QUOTA_EXHAUSTED` / 401 时触发 | 自动换券会消耗发券额度；若发现被滥用改为手动点击 |
| 演示分支永不合并 | 主干每次变更需手工解 4 处冲突 | 若某天要合并，需将 `@DemoQuota` 与熔断改为开关式 |

---

## 16. 工期对照

| 里程碑 | 本文档对应章节 | 预估 |
| --- | --- | --- |
| M0 基建隔离 | §2.3、§11 步骤 1–2 | 0.5 天 |
| M1 预置内容 | — （内容工作） | 1.5 天 |
| M2 后端 | §3–§9 | 2.5 天 |
| M3 前端 | §10 | 1.5 天 |
| M4 验收 | §12 | 1 天 |
| **合计** | | **7 天** |
