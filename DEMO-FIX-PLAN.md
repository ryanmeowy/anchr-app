# 演示模式必修问题 · 根因与修复方案

> 状态：待评审
> 范围：`demo` 分支 P0 六项 + P1 三项
> 依据：三路并行审查（后端 / 前端 / 契约），全部结论均实测复现

---

## 阅读说明

每条包含四部分：

- **现象** —— 用户或数据上会发生什么
- **根因** —— 代码层面为什么
- **修复方案** —— 具体改哪里、怎么改
- **验证方式** —— 改完怎么确认好了

工作量标记：`S` = 一小时内，`M` = 半天，`L` = 一天以上。

---

# P0 · 不修不能上线

## P0-1 · 种子文档可被任意 USER 令牌删除

**工作量**：M

### 现象

一个刚领到演示令牌的访客，可以在知识库里删除**预置的**文档（`07-glossary.txt` 等 17 篇）。删完之后，下一个访客看到的就是残缺的知识库。

实测已复现，并已用 SQL 恢复：

```
DELETE /kbs/1000002093/documents/1000015626 → 200
```

反直觉的是：**访客自己上传的文件反而删不掉**（`deletable: false`），种子文档却可以（`deletable: true`）。

### 根因

三处默认值撞在一起，形成了一条「身份恒等于 system」的链条：

```java
// 1. auth 签发令牌时不写 userId
//    AuthController.java:134-135
tokenData.put("role", requestedRole);
tokenData.put("createdAt", System.currentTimeMillis());
// ← 没有 userId

// 2. 拦截器读不到就回落成常量
//    AccessTokenInterceptor.java:91-94
userId = storedUserId instanceof String text && !text.isBlank()
        ? text.trim()
        : RequestUserContext.DEFAULT_USER_ID;   // = "system"

// 3. 数据库 created_by 的默认值也是 "system"
//    V2__create_knowledge_base_tables.sql:38, 9
created_by varchar(64) not null default 'system'

// 4. 归属校验变成恒真
//    KnowledgeBaseServiceImpl.java:222
context.userId().equals(asset.getCreatedBy())   // "system".equals("system") → true
```

DB 实测分布：`asset.created_by` → `system: 17 条`、`demo_*: 1 条`。

### 修复方案

**第一步（必做）：给归属校验加哨兵拒绝**

`KnowledgeBaseServiceImpl.java:222` 与 `ConversationSessionUseCase.java:90` 两处，在比对前先排除不可作为身份的值：

```java
/** 系统占位身份，不代表任何真实用户，不能用于归属判定。 */
private static final Set<String> NON_IDENTITIES = Set.of("system", "guest", "anonymous");

private static boolean isOwnedBy(String callerId, String ownerId) {
    if (callerId == null || ownerId == null) return false;
    if (NON_IDENTITIES.contains(callerId) || NON_IDENTITIES.contains(ownerId)) return false;
    return callerId.equals(ownerId);
}
```

两处调用改为 `if (!isOwner(context) && !isOwnedBy(context.userId(), asset.getCreatedBy()))`。

**第二步（建议同批）：签发时注入唯一身份**

`AuthController.refresh-token` 补一个 `userId`，让 refresh-token 签发的令牌也具备真实身份：

```java
tokenData.put("userId", "user_" + shortId());
```

同时 `AccessTokenInterceptor` 的回落策略要改：**没有 userId 的令牌不应回落成 `system`**，而是拒绝或标为匿名。

**第三步（可选）：DDL 默认值改为哨兵**

把 `created_by` 的默认值从 `'system'` 改为 `'__unowned__'`，让「无主数据」不可能与任何真实身份匹配。

> 注意：这一步需要新的 migration，且要回填现有数据，建议单独排期。

### 验证方式

- 用 refresh-token 的 USER 令牌查文档列表，种子的 `deletable` 必须是 `false`
- 直接 DELETE 种子文档 → 必须 404
- 访客自上传的文件 → `deletable: true` 且能删成功

---

## P0-2 · STS 凭证绕过全部配额

**工作量**：S

### 现象

演示令牌可以不消耗任何额度，反复获取 STS 临时凭证。拿到凭证后可以直接对 OSS 桶上传文件，**完全绕过 10 次额度和 `upload-max-files:1` 的限制**。

实测：全新零消耗令牌连打 10 次 STS 全部 200，配额计数器始终为 `nil`。

### 根因

两个问题叠加：

```java
// 1. STS 端点没有 @DemoQuota
//    AuthController.java:234-236
@RequireAuth(roles = {"ADMIN", "USER"})
@GetMapping("/sts")                                  // ← 无 @DemoQuota
public Result<Map<String, Object>> getStsToken() {

// 2. 下发的凭证没有任何权限约束
//    AuthStorageAcl.java:21-23 → storageRuntimeApi.issueTemporaryCredential()
//    AssumeRole 未设 Policy / PolicySessionPolicy
```

`prefix` 只是返回给前端看的字符串，**服务端不强制**。拿到凭证后可以写桶里任何路径。

这也解释了为什么 `DemoQuotaCoverageContractTest` 没抓到：该测试只从 `@DemoQuota` 正向遍历，从不反问「这个端点该不该计费」。

### 修复方案

**第一步（必做）：加计费注解**

```java
@RequireAuth(roles = {"ADMIN", "USER"})
@DemoQuota
@GetMapping("/sts")
public Result<Map<String, Object>> getStsToken() {
```

注意：`getStsToken` 是 GET，而当前的计费覆盖测试只识别 POST/PUT，**加完注解后测试也覆盖不到它**。建议同步把测试的 `isWrite()` 扩展到 GET（见文末「测试盲区」）。

**第二步（强烈建议）：给 STS 加最小权限 Policy**

在 `AssumeRole` 调用处下发 session policy，把写入范围锁死在调用者自己的目录：

```json
{
  "Version": "1",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["oss:PutObject", "oss:AbortMultipartUpload"],
    "Resource": ["acs:oss:*:*:anchr-demo/anchr-dev/${userId}/*"]
  }]
}
```

**第三步（建议）：缩短有效期**

演示场景不需要 3600 秒，改成 900 秒即可。

### 验证方式

- 新领的令牌连打 5 次 STS，配额计数器应从 0 涨到 5
- 用返回的凭证尝试写 `anchr-dev/任意其他路径` → 应被拒绝
- 写 `anchr-dev/{自己的userId}/xxx` → 应成功

---

## P0-3 · 人机校验脚本失败后永久不可恢复

**工作量**：S

### 现象

阿里云 CDN 加载失败一次（广告拦截、公司网络、偶发断网）后，用户点「重试」按钮会永久卡在「正在准备…」，**不刷新页面无法自救**。

这在目标用户群里是常态，不是边角场景。

### 根因

失败时没有把 `<script>` 标签移除，重试会走进一个永远等不到事件的分支：

```ts
// aliyun-captcha.ts:76-91
loading = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector(`script[src="${SDK_URL}"]`);
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () => reject(...));
      return;                                    // ← 给失败的标签挂监听
    }
    const script = document.createElement("script");
    script.onload = () => resolve();
    script.onerror = () => {
      loading = null;
      reject(new Error("captcha sdk failed"));
      // ← 没有 script.remove()
    };
    document.head.appendChild(script);
});
```

第一次失败后，那个 `<script>` 仍留在 DOM 里且已处于 error 状态。第二次调用命中 `existing` 分支，给它加 `load`/`error` 监听——但这些事件**永远不会再触发**（标签已经加载完了），Promise 永不 settle。

### 修复方案

```ts
script.onerror = () => {
    loading = null;
    script.remove();          // 关键：让下次能重新建标签
    reject(new Error("captcha sdk failed"));
};
```

同时给 `existing` 分支加防御——已存在的标签如果已经失败，应该移除重建而不是挂监听。更稳妥的做法是记录全局的失败状态：

```ts
let failedOnce = false;

// existing 分支
if (existing && !failedOnce) {
    existing.addEventListener("load", () => resolve());
    existing.addEventListener("error", () => reject(...));
    return;
}
if (existing) existing.remove();
```

另外建议给整个加载加**超时**（如 12 秒），防止任何情况下的静默挂起。

### 验证方式

- 拦截 `o.alicdn.com` 让首次加载失败 → 出现错误提示
- 解除拦截，点「重试」→ 应能正常加载出拼图
- 或：首次失败后点重试，状态不应永久停在「正在准备…」

---

## P0-4 · 弹窗关闭按钮关不掉阿里云全屏遮罩

**工作量**：S

### 现象

点「领取演示令牌」打开拼图后，点弹窗右上角 `X`，React 气泡消失了，但**阿里云的全屏遮罩仍盖在页面上**，页面其它区域完全不可交互，且没有任何关闭入口。访客被彻底卡死。

### 根因

阿里云 SDK 自己往 `document.body` 里插入覆盖层（`z-index: 10000000` 级别），不归 React 管：

```tsx
// demo-claim-dialog.tsx:116
<button type="button" className="demo-bubble-close" onClick={closeDialog}>
```

`closeDialog()` 只做 `setDialogOpen(false)`，没有任何代码去收掉 SDK 插入的 DOM 节点。`init()` 调用时也没有传 `onClose` 回调。

### 修复方案

**方案 A（推荐）：init 时传 onClose，并主动清理**

```tsx
init({
  // ...
  onClose: () => setDialogOpen(false),
});
```

在 `closeDialog` 里主动移除 SDK 的覆盖层：

```ts
const closeDialog = useCallback(() => {
  setDialogOpen(false);
  setPhase({ kind: "idle" });
  // SDK 的覆盖层不在 React 树里，需要显式清掉
  document.querySelectorAll(".aliyunCaptcha-top, #aliyunCaptcha-window-popup")
    .forEach((node) => node.remove());
}, []);
```

**方案 B：让关闭按钮在拼图打开时不出现**

如果不想处理 DOM 清理，可以简单地：拼图打开期间隐藏 `X` 按钮，强制用户先完成或按 ESC。但这体验更差。

**注意**：清理后下次打开会复用已被移除的实例，需要在 `closeDialog` 里递增 `attempt` 强制重建。

### 验证方式

- 点「领取演示令牌」→ 拼图弹出
- 点 `X` → 气泡和拼图遮罩**都**消失，页面可正常点击
- 再次点「领取演示令牌」→ 拼图能正常再次弹出

---

## P0-5 · 失败请求也扣额度

**工作量**：M

### 现象

任何被拒绝的请求（400 参数错误、404 不存在、500 内部错误）都会扣掉 1 次额度。

实测：空 body 打 `POST /search/kb` → 返回 400，配额计数器从 `0` 变成 `1`。

对演示的实际影响：访客误点两次、或者请求因为网络问题失败，就白丢 2 次额度。

### 根因

计费发生在 `preHandle`，此时还不知道请求会不会成功，而且**没有任何回滚钩子**：

```java
// DemoQuotaInterceptor.java:74-86
long tokenUsed = incrementTokenCounter(tokenHash);   // ← 先扣
if (tokenUsed > demoProperties.tokenQuota()) { ... }

long dailyUsed = incrementDailyCounter();
if (dailyUsed > demoProperties.dailyOperationLimit()) { ... }

return true;    // ← 后续请求失败也不会退回
```

`DemoQuota` 注解的注释写的是「successful entry costs one credit」，但实现与注释矛盾。

### 修复方案

实现 `AsyncHandlerInterceptor` 的 `afterCompletion`，按响应状态回滚。参考 `AccessTokenInterceptor.java:169` 已有的写法：

```java
/** 在 preHandle 里记下本次是否扣过费，供 afterCompletion 决定要不要退。 */
private static final String CHARGED_ATTR = "demo.quota.charged";

@Override
public boolean preHandle(...) {
    // ... 原有的扣费逻辑
    request.setAttribute(CHARGED_ATTR, tokenHash);   // 记录扣了谁
    return true;
}

@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                            Object handler, Exception ex) {
    Object charged = request.getAttribute(CHARGED_ATTR);
    if (!(charged instanceof String tokenHash)) return;

    // 只对"请求本身失败"退费；业务层的 4xx（如额度不足）不会被扣费，因而不受影响
    int status = response.getStatus();
    boolean failed = ex != null || status >= 400;
    if (failed) {
        decrementTokenCounter(tokenHash);
        decrementDailyCounter();
    }
}
```

**注意异步场景**：SSE 请求会在 `afterConcurrentHandlingStarted` 时提前返回，`afterCompletion` 要等异步完成才调用。需要确认 `DemoQuotaInterceptor` 是否实现了 `AsyncHandlerInterceptor`——它实现了，所以 `afterCompletion` 会在异步结束后触发，行为正确。

### 验证方式

- 发一个必然失败的请求（如空 body）→ 配额计数器不应变化
- 发一个成功请求 → 配额应 +1
- SSE 流式请求 → 结束后配额应 +1（不是 +2，也不能是 0）

---

## P0-6 · 每日计数 TTL 永久丢失，额度永久漂移

**工作量**：S

### 现象

每日操作计数和每日签发计数可能变成**永不过期**的 key，且数值永久偏移。线上 Redis 里已经存在 2 个这样的脏 key：

```
demo:quota:token:e05e746f877afcb61c382eaf63a69de497a9499a030  TTL=-1
demo:quota:token:eccba40108cf330dddd45f3db5b6cb6ad243cd49db7  TTL=-1
```

### 根因

TTL 只在「第一次自增」时设置，而回滚的 `DECR` 可能作用在一个**不存在的 key** 上，Redis 会创建出值为 `-1` 且**没有 TTL** 的 key：

```java
// DemoQuotaInterceptor.java:123-131
private long incrementDailyCounter() {
    Long value = redisTemplate.opsForValue().increment(key);
    long used = value == null ? 1L : value;
    if (used == 1L) {                          // ← 只在首次设 TTL
        redisTemplate.expire(key, untilNextDay());
    }
    return used;
}

private void decrementDailyCounter() {
    redisTemplate.opsForValue().decrement(dailyKey());   // ← key 不存在时造出 -1 且无 TTL
}
```

后续 `INCR` 得到 `0`（从 -1 涨到 0），`used == 1L` 不成立，`expire()` **永远不会执行**。

实测复现：

```
DEL key  → DECR → -1, TTL=-1
         → INCR →  0, TTL=-1     ← TTL 永久丢失
```

同一个 bug 也在 `DemoTokenService.decrementIssuedToday` 里，影响每日签发上限。

### 修复方案

**把 TTL 设置与计数值解耦**，不依赖 `used == 1`：

```java
private long incrementDailyCounter() {
    String key = dailyOpsKey();
    Long value = redisTemplate.opsForValue().increment(key);
    // NX 保证只在没有 TTL 时设置，既覆盖首次创建，也修复被 DECR 造出的无 TTL key
    redisTemplate.expire(key, untilNextDay());
    return value == null ? 1L : value;
}
```

Redis 7 支持 `EXPIRE key ttl NX`，若不想依赖版本，可以每次都无条件 `expire`（幂等，只是多一次往返）。

同时修 `decrementDailyCounter`，避免造出负数 key：

```java
private void decrementDailyCounter() {
    String key = dailyOpsKey();
    Long value = redisTemplate.opsForValue().decrement(key);
    if (value != null && value <= 0) {
        redisTemplate.delete(key);   // 回滚到 0 以下说明是脏数据，直接删掉
    }
}
```

**清理已有脏 key**：上线后需要一次性清理线上那些 `TTL=-1` 的计数器（可以用 `SCAN` + `TTL` 找出并删除）。

### 验证方式

- 模拟跨天：`DEL` 计数器 → 发一个会触发的请求 → `DECR` → 再 `INCR`
- 确认 `TTL` 之后**不是 -1**
- 检查线上 `demo:quota:*` 和 `demo:issue:*` 没有 `TTL=-1` 的 key

---

# P1 · 强烈建议同批修

## P1-1 · 任意 401 就清令牌并丢失草稿

**工作量**：M

### 现象

后台轮询的任意一个请求返回 401（比如令牌在别的服务上过期、反向代理打错上游、某个端点自己返回 401），就会：

1. 清掉用户的令牌
2. 清空整个 React Query 缓存
3. 重挂整棵工作区子树
4. **用户正在输入的草稿丢失**
5. 页面变成「索引状态未知，已暂停加载」

实测：注入合法 USER 令牌、只让 `/index/status` 返回 401，草稿确实被清空。换成 403 则一切保留。

### 根因

`request()` 把**任何** 401 都当成「演示令牌过期」，且后续动作用的是最重的手段：

```ts
// api-client.ts:313-316
if (isDemoQuotaExhausted(error) || isAuthenticationError(error)) {
    emitDemoExhausted(isDemoQuotaExhausted(error) ? "quota" : "expired");
}

// → demo-token-context.tsx:164-173
const onExhausted = () => {
    clearAccessToken();
    setUsage(null);
    setPhase({ kind: "idle" });
    setDialogOpen(false);
};

// → providers.tsx:66-77
const resetAuthenticatedState = () => {
    void queryClient.cancelQueries();
    queryClient.clear();          // ← 清空所有缓存
    setAuthEpoch((c) => c + 1);   // ← 递增导致子树重挂
};
```

「令牌失效」被升级成「整个工作区重置」。

### 修复方案

**第一步：401 时先复核，不要立即清**

```ts
if (isAuthenticationError(error)) {
    // 不是所有 401 都意味着「这个令牌死了」——可能是某个端点自己的鉴权问题
    const role = await validateAccessToken(token).catch(() => null);
    if (!role?.valid) emitDemoExhausted("expired");
    // valid 则忽略，不清令牌
}
```

**第二步：用缓存失效代替子树重挂**

`providers.tsx` 里把 `queryClient.clear()` + `authEpoch + 1` 改成：

```ts
queryClient.invalidateQueries();   // 只重新取数，不销毁组件
```

`authEpoch` 只在**真实身份变更**（不同用户登录）时才递增，令牌过期不应该触发。

**第三步：保留草稿**

如果确实要重挂，先把草稿写进 `sessionStorage`，挂载后恢复。

### 验证方式

- 注入有效令牌，让某个后台端点返回 401
- 令牌应保留（若复核确认为有效），或至少草稿不丢
- 页面不应进入「暂停加载」

---

## P1-2 · 余额刷新时机错误

**工作量**：S

### 现象

扣费之后侧边栏的剩余次数不更新，或者更新成旧值。实测：把 `/search/kb` 人为延迟 700ms，响应顺序变成 `usage → search`，侧栏仍显示「剩余 10 次」，而服务端其实已经扣到 9。

### 根因

三处调用点都是**在请求发出之前**就刷新，与注释描述的意图相反：

```tsx
// ask-premium-page.tsx:2784-2787
// The charge happens when the request is accepted...
void Promise.resolve().then(() => refreshUsage());   // ← GET 先发
const streamRequest = apiClient.sendMessageStream(    // ← POST 后发
```

GET 与 POST 并发，GET 大概率先返回，读到的是扣费前的值。

### 修复方案

把刷新移到**请求成功返回之后**：

```tsx
// ask：放到流式响应回调里
const streamRequest = apiClient.sendMessageStream(
  targetSessionId,
  { ... },
  {
    onResponse: () => { void refreshUsage(); },   // 拿到响应头即可刷新
    // ...
  }
);
```

```tsx
// search：放到 mutation 的 onSuccess
const searchMutation = useMutation({
  mutationFn: (variables) => apiClient.searchKnowledgeBase({ ... }),
  onSuccess: () => { void refreshUsage(); },
});
```

```tsx
// imports：放到 createIngestionTask 的 .then 里
() => apiClient.createIngestionTask(kbId, request).then((task) => {
  void Promise.resolve().then(() => refreshUsage());
  return task;
})
```

**更好的做法**：把刷新下沉到 `api-client` 的响应层，识别「本次请求发生了计费」后统一派发事件。这样新增计费端点不会漏（目前 `retry` / `reparse` / `reembed` 都计费但都不刷新）。

### 验证方式

- 人为延迟计费请求 700ms，操作后侧栏数字必须是扣费后的值
- 重试失败的导入任务、重解析、重嵌入后，侧栏也应更新

---

## P1-3 · 额度耗尽的文案与错误码处理

**工作量**：S

### 现象

三个问题：

1. **两处文案互相矛盾**：一处说「可再次领取」，另一处说「必须等过期」
2. **`DEMO_TOKEN_STILL_ACTIVE` 前端完全没处理**：落到兜底的「领取失败，请稍后重试」——这个提示是**错的**，等一会儿没用，必须等令牌过期
3. 侧栏按钮的 `title` 写的是「重新领取」，但服务端会拒绝

### 根因

```tsx
// demo-token-context.tsx:211 —— 错的说法
if (code === "DEMO_QUOTA_EXHAUSTED") {
    setPhase({ kind: "error", reason: "本次试用次数已用完，可再次领取。" });
}

// demo-claim-dialog.tsx:199 —— 对的说法（两处矛盾）
<p>演示令牌仍然有效，有效期结束后即可重新领取一枚。</p>

// 后端有 6 个 DEMO_ 错误码，前端只处理了 5 个
// DEMO_TOKEN_STILL_ACTIVE 零引用
```

### 修复方案

**第一步：统一文案**

`demo-token-context.tsx:211` 改为与 dialog 一致：

```tsx
if (code === "DEMO_QUOTA_EXHAUSTED") {
  setPhase({ kind: "error", reason: "本次试用次数已用完，有效期结束后可重新领取。" });
}
```

**第二步：补上缺失的错误码**

```tsx
} else if (code === "DEMO_TOKEN_STILL_ACTIVE") {
  setPhase({
    kind: "error",
    reason: "演示令牌仍然有效，有效期结束后即可重新领取。"
  });
}
```

**第三步：修正侧栏 title**

`demo-token-button.tsx` 里把 `"演示令牌已用完，重新领取"` 改为 `"额度已用完 · 有效期结束后可重新领取"`。

### 验证方式

- 用额度耗尽的令牌点「领取」→ 提示必须是「等过期」而不是「稍后重试」
- 检查侧栏按钮悬浮提示与气泡文案一致

---

# 附录 · 测试盲区

`DemoQuotaCoverageContractTest` 目前绿灯，但它有两个盲区，导致 P0-2、P0-5、P0-6 都没被发现：

**盲区一：只认 POST/PUT**

```java
// isWrite():117-120
return method.isAnnotationPresent(PostMapping.class)
    || method.isAnnotationPresent(PutMapping.class);
```

PATCH 和 DELETE 全部漏检。P0-2 的 `/auth/sts` 是 GET，也漏检。

**盲区二：只做正向遍历**

```java
// everyVisitorWritableEndpointMustEitherBillOrBeJustified
for (每个方法) {
    if (!isWrite(method) || !isVisitorCallable(...)) continue;
    if (!billed && !FREE_ALLOWED.contains(label)) → 报错
}
```

只检查「标了 @DemoQuota 的是否合理」，从不反问「这个会花钱的端点为什么没标」。

**建议补的两个测试**：

1. `isWrite()` 扩展到 PATCH/DELETE/GET，对 GET 只检查「明确会调用外部服务的」（如 `sts`、`refresh`）
2. 反向测试：维护一份「必须计费的端点清单」，断言它们都有 `@DemoQuota`

另外注意：**ADMIN 令牌按设计不计费**，任何用 ADMIN 做的配额验证都是假阳性，必须用 USER 令牌测。

---

# 建议的修复顺序

| 顺序 | 项目 | 理由 |
|---|---|---|
| 1 | P0-6（TTL）、P0-5（失败扣费） | 同在 `DemoQuotaInterceptor`，一次改完 |
| 2 | P0-2（STS） | 一行注解 + Policy，风险最低收益最高 |
| 3 | P0-3（脚本重试）、P0-4（遮罩） | 前端两处，互不干扰 |
| 4 | P0-1（身份） | 需要设计，最后做 |
| 5 | P1-1、P1-2、P1-3 | 体验优化，可与 P0 并行 |

改完 P0 六项，演示就能给面试官用；补完 P1，体验才合格。
