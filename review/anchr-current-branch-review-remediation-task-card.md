# ANCHR-207：当前分支 Review 整改任务卡

## 文档状态

- 状态：执行中
- 审查基线：`anchr-app dev/clean-up@db0cbdcc76126d54c619b709a8cf3165a246c5f8`
- 形成日期：2026-07-30
- 适用项目：`anchr-app`；涉及公开 Search 契约时交叉检查 `anchr-web`

本文只把当前分支已经核实的问题转成可执行任务，不重新引入已经删除的 101–206 历史任务卡，也不把建议误写成已经完成的实现。

## 总体结论

当前代码的主要问题不是“缺少更多抽象”，而是以下四类风险没有被清晰处理：

1. 安全默认值错误：认证是 fail-open，日志会记录用户原始查询。
2. 检索契约与真实语义不一致：`sort` 无效，`total/facets` 只统计最终 Top-N 窗口，active generation 后置过滤会损失召回。
3. 运行和文档承诺不一致：生产默认配置偏开发环境，README 夸大 Ingestion 的恢复能力，仓库没有自动 CI 门禁。
4. Agent 结构没有承载已经存在的复杂规则：编排器仍然过大，验证结果缺少明确类型，外部依赖协作者被当作 Helper 手工创建，且存在数字方括号被误删的内容完整性缺陷。

本卡不以增加 Helper、Port、层级或框架数量作为完成标准。只有安全默认值、业务结果、依赖方向、状态表达和自动化门禁得到实际改善，才算完成。

## 子卡总览

| 子卡 | 目标 | 优先级 | 状态 | 是否需要先对齐 |
|---|---|---:|---|---|
| 207A | 认证改为 fail-closed | P0 | 止血完成 | Spring Security 迁移和内部系统上下文重构均需单独确认 |
| 207B | 禁止日志记录用户原始查询和模型原始输出 | P1 | 完成 | 否 |
| 207C | 修复 Agent 数字方括号和引用清洗误删 | P1 | 完成 | 否 |
| 207D | 修复 active generation 后置过滤造成的召回损失 | P1 | 待执行 | 补偿召回或 ES 前置过滤方案需确认 |
| 207E | 让 Search API 的 sort、total、facets 与真实语义一致 | P1 | 完成 | 已选择 Top-N |
| 207F | 收敛生产运行默认值和搜索参数来源 | P1/P2 | 待执行 | 生产部署方式需确认 |
| 207G | 建立最小 CI 和合并门禁 | P1 | 待执行 | 跨仓工作流范围需确认 |
| 207H | 准确描述 Ingestion 的恢复能力 | P2 | 待执行 | 否 |
| 207I | 收敛 Agent 状态、依赖和可读性 | P2 | 待执行 | 每阶段单独执行，不一次性重写 |

推荐执行顺序：

```text
第一批：207A → 207B → 207C
第二批：207D → 207E
第三批：207F → 207G → 207H
第四批：207I
```

207I 必须最后执行。安全和正确性问题不能与结构重构混在同一个提交中。

---

## ANCHR-207A：认证默认拒绝

### 当前事实

- `AccessTokenInterceptor` 只读取方法级 `@RequireAuth`。
- 没有注解的方法直接设置 `RequestUserContext.systemDefault()` 并放行。
- `systemDefault()` 使用 `userId=system`、`role=ADMIN`。
- `@RequireAuth` 只允许标注 `METHOD`，不能标注 Controller 类。
- `UserContextHolder.get()` 在上下文缺失时也返回 system/ADMIN。
- 当前已检查的业务 Controller 方法都有 `@RequireAuth`；四个无注解方法位于 `AuthController`，其中三个自行校验 Admin Secret。当前没有确认已经匿名暴露的敏感业务方法，但未来漏注解会直接形成高权限匿名访问。

### 实施范围

本次只完成不依赖框架迁移的立即止血：

1. `/api/**` 的 `HandlerMethod` 默认拒绝。
2. 只有显式认证规则或显式匿名规则才能进入 Controller。
3. 增加明确的匿名标记；当前 Auth 公共端点逐个显式声明，不能依赖“缺少认证注解”表示公开。
4. 支持类级别认证规则，并明确方法级规则是否允许覆盖类级规则。
5. 匿名请求不能获得 system/ADMIN 上下文。
6. 审计 `UserContextHolder.get()` 的全部调用方，区分：
   - 已认证请求上下文；
   - 显式匿名上下文；
   - 调度器、Outbox 等系统执行上下文。
7. 增加测试期 HandlerMethod 扫描：所有 `/api/**` 方法必须显式声明“需要认证”或“允许匿名”，两者不能同时存在。

本次不修改 `UserContextHolder.get()` 对内部任务保留的 system fallback。该 fallback 仍被 Ingestion 活动回调等非 HTTP 执行路径使用，直接删除会扩大为调度、事务后回调和异步上下文重构。止血后，未声明规则的 HTTP 请求会在 Controller 前被拒绝，不再触发该 fallback。系统执行上下文显式化应与 Spring Security 迁移分别评估并单独确认。

Spring Security 是推荐的后续实现方向，但不是立即止血的前置条件。是否在本卡内完成完整迁移，需要单独确认；不得为了引入 Spring Security 同时改 Token JSON、Redis Key、角色语义或现有错误响应。

### 硬边界

- 不修改端点路径、HTTP method、请求 JSON 或成功响应 JSON。
- 保持当前 `X-Access-Token`、Redis Token 和 `ADMIN/USER/GUEST` 角色协议。
- 保持认证失败的 HTTP 401、角色失败的 HTTP 403，以及 102 已确定的上传清理元数据。
- 不让 Actuator、静态资源或非 `/api/**` 路径意外继承 system/ADMIN。

### 验收

- 新增一个没有任何认证/匿名声明的测试 Controller，访问时必须返回 401。
- 类级认证规则对所有方法生效。
- 显式匿名端点可以访问，但上下文不是 ADMIN。
- 当前需要认证的 62 个 mapping 无 Token 返回 401，角色不符返回 403。
- Auth 的四个现有公共流程协议保持不变。
- 异步线程、SSE 和调度任务不会继承或伪造请求用户上下文。
- 完整 HTTP contract 和全量测试通过。

### 2026-07-30 止血实施记录

- 新增项目内 `@PermitAll`，未引入 Spring Security 或其他依赖。
- `@RequireAuth` 支持类和方法；方法级规则覆盖类级规则，同一级冲突时默认拒绝。
- `/api/**` 的业务 `HandlerMethod` 无任何规则时返回现有 401 错误协议，不再设置 system/ADMIN。
- Auth 的 `validate-token`、`refresh-token`、`clean-token`、`list-tokens` 四个既有公开方法逐个标记 `@PermitAll`；未给整个 Controller 放行。
- 匿名端点使用明确的 `anonymous/ANONYMOUS` 请求上下文；未改变 `RequestUserContext` record 字段、Token JSON、Redis Key 或角色协议。
- 保持端点路径、HTTP method、请求/响应 JSON、401/403、上传清理元数据、SSE 上下文传递和前端协议不变。
- 增加默认拒绝、显式匿名、类级认证、方法级覆盖、冲突拒绝和生产 Controller 声明扫描测试。
- `mvn -DskipTests compile` 通过。
- 认证定向测试通过。
- 完整 `mvn test`：525 个测试，0 failure，0 error；17 个依赖 Docker/Testcontainers 的既有测试按环境条件跳过。

---

## ANCHR-207B：敏感查询日志治理

### 当前事实

以下路径会写入用户原始查询或模型原始输出：

- Retrieval 召回完成 INFO 日志记录 `rawQuery`。
- Search rewrite 失败 WARN 日志记录原始 query。
- Follow-up 失败 WARN 日志记录原始 query 和异常。
- rewrite JSON 解析失败 WARN 日志记录模型原始输出。

知识库查询可能包含公司资料、客户信息、个人信息和敏感关键词，不应默认进入集中日志。

### 实施范围

1. INFO/WARN/ERROR 日志不记录原始 query、完整 Prompt、模型原始输出或文档正文。
2. 保留必要的结构化诊断信息：
   - trace/request ID；
   - query 字符长度；
   - KB/Asset scope 数量；
   - recallTopK、各路候选数、generation 丢弃数；
   - fallback/error 分类；
   - latency。
3. 不使用可逆编码代替明文；如果确有跨日志关联需求，方案必须先通过安全评估。
4. Activity Recent Search 是产品数据，不属于日志；本卡不删除 Activity 记录，但应保持权限和保留期边界。

### 验收

- 使用 Logback test appender 覆盖成功召回、rewrite 失败、follow-up 失败和非法模型响应。
- 测试查询包含唯一敏感字符串，所有捕获日志均不得出现该字符串。
- 召回数量、延迟和错误分类指标仍然存在。

### 2026-07-30 实施记录

- Retrieval 成功日志不再记录 `rawQuery`，改为记录 query 长度、KB/Asset scope 数量、recallTopK、三路候选数和耗时。
- generation gate 日志明确区分 recalled、visible 和 discarded；只修正诊断口径，过滤和排序流程不变。
- Search rewrite、Conversation rewrite 和 Follow-up 失败日志不再记录 query、模型原始输出、文档 snippet、异常 message 或异常堆栈，改为记录长度、结果数和异常类型。
- rewrite 缓存异常日志不再记录包含查询 MD5 的缓存键；缓存键生成、TTL 和 Redis 数据协议不变。
- Conversation rewrite 解析失败或缺少 `rewrittenQuery` 时保持原查询并正确标记 `fallbackUsed=true`；合法改写仍标记为 `false`。
- 保留 Conversation 既有 sessionId；没有为日志新增跨层 request ID、MDC 或通用脱敏框架。
- Activity Recent Search 及其产品数据、端点、请求/响应 JSON、模型调用、缓存行为和 fallback 结果均未修改。
- Logback 合同测试覆盖成功召回、Search rewrite 调用失败、Search/Conversation 非法模型输出和 Follow-up 失败；同时检查格式化消息与异常链均不包含唯一敏感字符串。
- `mvn -DskipTests compile` 通过。
- 207B 定向测试通过。
- 完整 `mvn test`：532 个测试，0 failure，0 error；17 个依赖 Docker/Testcontainers 的既有测试按环境条件跳过。

---

## ANCHR-207C：Agent 引用文本完整性

### 当前事实

`AgentWorkflowImpl.stripHistoricalCitationLabels()` 和 `AgentCitationRenderer` 都使用：

```regex
\[\d+(?:-\d+)?]
```

该规则无法区分系统引用和普通文本，会删除 `[1]`、`[2024-2025]`、数组下标和普通编号。历史内容会在送入模型前被静默修改，最终回答渲染也可能删除合法文本。

### 实施范围

1. 删除“对任意数字方括号做全局替换”的规则。
2. 历史回答只根据该 Turn 的结构化 citation 数据清理实际生成过的可见引用标签。
3. 最终回答只把合法的内部 `{{segment:id}}` Marker 转成可见引用。
4. 模型直接生成的裸 `[1]` 不得被当成可点击引用；如果产品要求禁止伪引用，应校验并触发明确的修复/fallback，而不是静默删除所有数字方括号。
5. 不修改历史落库数据，不进行数据库批量清洗。

### 验收

Golden tests 至少覆盖：

- 系统生成的 `[1-1]` 能按结构化 citation 精确清理；
- 普通 `[1]`、`arr[1]`、`[2024-2025]`、Markdown link label 保留；
- 伪造的 segment ID 不会变成引用；
- 合法 Marker 与 `citedSegmentIds` 一一对应；
- 历史上下文和最终展示都不泄露内部 segment ID。

### 2026-07-30 实施记录

- 历史回答不再按数字方括号正则全局清洗；Agent 按持久化 citation 的 `assetCitationIndex-segmentCitationIndex` 精确删除，传统回答只在没有分段标签时按资产级索引删除。
- citation JSON 缺失或损坏时保留历史回答原文；普通 `[1]`、数组下标、年份区间和 Markdown link label 不再被误删，也未修改任何历史落库数据。
- `AgentCitationRenderer` 只把当前 Run 已注册证据的合法 `{{segment:id}}` Marker 转成可见引用；未知 Marker 和裸露的内部 segment ID 不会变成引用或泄露到最终回答。
- `citedSegmentIds` 与正文 Marker 的去重集合必须完全一致；模型预写且与生成标签冲突的裸引用会进入既有 repair/fallback，异步总结中的未注册 Marker 或冲突标签会进入明确失败/重试路径。
- `anchr-web` 在存在分段 `citationLabel` 时只注册层级标签，传统引用继续注册资产级标签；Markdown 解析不再把 `arr[1]` 一类数组下标转换为引用链接。
- 未修改 REST、SSE、数据库 Schema、Citation DTO、分层引用格式或现有历史数据。
- 207C 后端定向测试通过；完整 `mvn test`：537 个测试，0 failure，0 error；17 个依赖 Docker/Testcontainers 的既有测试按环境条件跳过。
- `anchr-web` 78 个 Node 测试、ESLint 和 Next.js 生产构建通过。

---

## ANCHR-207D：Active Generation 召回补偿

### 当前事实

当前顺序是：

```text
ES text/vector Top-K
→ RRF
→ 查询 MySQL active generation
→ 丢弃旧 generation
→ diversify/rerank
```

旧 generation 可以占满 ES Top-K，使排名稍后的 active generation 永远无法进入候选集。现有 candidate multiplier 只能降低概率，不能保证结果补足。

### 实施前决策

必须先从以下方案中确认一个，不得自行引入 ES mapping：

1. **推荐的克制方案：自适应补偿召回**
   - generation gate 后候选不足时扩大 recallTopK；
   - 复用同一个 query embedding；
   - 设置最大窗口、最大轮数和延迟上限；
   - 直到 active 候选满足 rerank/limit 需要或达到安全上限。
2. **ES 前置过滤方案**
   - 将可见性投影进 ES，查询时直接过滤；
   - 必须同时设计激活、旧 generation 退役、失败回滚、重建和 alias 切换；
   - 需要 mapping 或写入协议变化时单独立项，不在未确认时实施。

不建议为宽 KB 构造无限 `(assetId, generation)` bool clauses。

### 验收

- 构造旧 generation 占满首轮 Top-K、active generation 位于窗口外的测试，修复前结果不足，修复后能补足。
- 没有 active generation 的 Asset 继续 fail-closed。
- query embedding 每次请求只生成一次。
- 增加首轮候选、generation 丢弃、补偿轮数、最终 active 候选和延迟指标。
- BM25、向量双路、RRF、图片分路、rerank 和 Asset 聚合顺序除补偿召回外不变。
- 真实 ES 验证旧/新 generation 并存时的召回结果和延迟上限。

---

## ANCHR-207E：Search API 语义收敛

### 当前事实

- 请求包含 `sort`，但搜索实现从未读取它。
- `total` 等于最终按 Asset 聚合并截断后的返回数量。
- facets 只基于最终返回 items 统计。
- 请求没有 page、offset、cursor 或 search-after。
- 当前接口本质是 bounded Top-N retrieval，不是真正的分页搜索，但 DTO 名为 `SearchPageDTO`，前端将 `total` 显示为结果数量。

### 产品决策门禁

实施前必须选择：

#### 方案 A：明确为 Top-N Retrieval（推荐）

- 保持单次 bounded retrieval。
- `sort` 从公开能力中删除、废弃或对非空值明确拒绝，不能继续静默忽略。
- `total` 明确定义为 returned/retained count，而不是全量命中数。
- facets 明确定义为当前返回窗口分布。
- 前端文案不能表达为“全部搜索结果数量”。

#### 方案 B：实现真实搜索结果页

- ES 提供 total hits、aggregations 和稳定排序。
- 使用 search-after/cursor，不使用深 offset。
- 必须额外解决 RRF、rerank、Asset 聚合后的跨页稳定性。
- 该方案工作量较大，应从本卡拆出独立设计，不得直接在现有流程上伪造 page/total。

任何请求/响应 JSON 字段调整都必须先确认，并同步修改 `anchr-web` 类型、展示、测试和兼容策略。端点 `POST /api/v1/search/kb` 默认不变；如需修改路径，必须再次确认。

### 验收

- `sort` 不再作为 Search API 或内部 Retrieval 的公开能力。
- `total/facets` 的字段语义、后端实现、接口文档和前端展示一致。
- 为选定方案增加 HTTP golden contract。
- Search Answer、Follow-up、Activity 记录和 Conversation 内部 Retrieval 不受影响。

### 2026-07-30 实施记录

- `/api/v1/search/kb` 明确定义为 `limit=1..10` 的 bounded Top-N 检索；未增加 page、offset、cursor、nextCursor 或 totalHits。
- 从 `SearchQueryDTO`、`anchr-web` 请求类型和内部 Retrieval Query 中删除 `sort`；检索固定使用现有相关性排序，不再暴露只有一个选项的伪配置。
- 搜索响应将 `total/facets` 直接替换为 `returnedCount/windowFacets`，不双写旧字段；`returnedCount` 始终由最终 `items` 数量生成，`windowFacets` 只统计最终返回窗口。
- Retrieval 应用接口、结果模型和装配器从 Page 命名收敛为 Top-N；BM25、向量双路、RRF、generation gate、rerank 和 Asset 聚合顺序未改变。
- `anchr-web` 同步使用新响应字段，排序请求类型收窄为 `RELEVANCE`，来源标题明确显示“本次返回 N 条”。
- Search Activity 的历史 `total` 字段继续记录当次返回数量；未迁移或重写历史活动数据。
- 后端定向测试通过；完整 `mvn test`：537 个测试，0 failure，0 error；17 个依赖 Docker/Testcontainers 的既有测试按环境条件跳过。
- `anchr-web` 80 个 Node 测试、ESLint 和 Next.js 生产构建通过。

---

## ANCHR-207F：生产运行默认值与参数来源

### 当前事实

- 基础 `application.yaml` 固定激活 `dev`。
- 默认 MySQL URL 使用 `useSSL=false` 和 `allowPublicKeyRetrieval=true`。
- Flyway 默认 `baseline-on-migrate=true`。
- 仓库没有独立生产 profile。
- RRF 60、Rerank window 40、融合权重 0.6/0.4 是经验默认值；仓库没有离线评估集或指标报告。

### 实施范围

#### 207F1：生产配置

1. 基础配置不固定激活 dev；本地开发配置进入明确的 dev profile。
2. 生产数据库连接不默认关闭 SSL，也不默认允许公钥拉取。
3. 生产 Flyway 默认不自动 baseline 非空数据库。
4. 明确哪些配置必须由环境提供，缺失时启动失败。
5. 保持 Docker Compose 本地开发可用，但本地便利配置不能成为生产默认值。
6. 核对 profile 参与 Redis ID Generator key 的行为，防止生产误用 dev ID 段。

#### 207F2：搜索参数治理

1. 不在没有评估数据时修改 60、40、0.6/0.4。
2. 让关键窗口和权重具备明确的环境覆盖方式和边界校验。
3. 建立最小离线评估说明：查询集来源、相关性标注、Recall@K、nDCG/MRR、延迟和回归阈值。
4. 文档明确当前参数是经验基线，不宣传为已优化参数。

### 验收

- dev 和 production 配置分别启动验证。
- production 缺少必要配置时 fail-fast。
- production 数据源配置不含仓库写死的 `useSSL=false`。
- 非法搜索权重、负窗口或权重和异常时拒绝启动或回退到明确安全值。
- 参数调整必须附带评估结果，不能只修改 YAML。

---

## ANCHR-207G：最小 CI 与合并门禁

### 当前事实

当前仓库没有 `.github/workflows`，因此仓库内没有随 push/PR 自动执行的 GitHub Actions 验证。是否存在外部 CI 需另行确认。

### 实施范围

1. 为 `anchr-app` 建立最小 PR workflow：
   - JDK 21；
   - Maven compile；
   - 单元与 contract tests；
   - Docker/Testcontainers 可用时执行 MySQL 集成测试；
   - 汇总 tests/failures/errors/skipped。
2. 缓存只用于依赖，不缓存测试产物作为通过依据。
3. 失败和 Testcontainers 跳过必须在检查结果中可见。
4. 配置 branch protection，将必需检查设为合并门禁。
5. `anchr-web` 和 `anchr-docling` 应在各自仓库建立 typecheck/test/build 与 pytest/Ruff 工作流；是否由本卡跨仓实施必须先确认。

### 硬边界

- 本卡不自动部署生产。
- 不把需要真实 OSS、模型服务或长期 ES 集群的验收伪装成普通单测。
- 真实基础设施 smoke test 可以作为受控的独立 workflow，不把凭据暴露给 fork PR。

### 验收

- 新 PR 自动触发且失败能阻止合并。
- 至少一次干净 workflow 运行覆盖完整 app 测试。
- 明确显示 Docker 集成测试是执行还是跳过。
- README 或贡献说明记录本地与 CI 验证命令。

---

## ANCHR-207H：README 恢复能力表述

### 当前事实

当前 Ingestion：

- 状态和阶段可追踪；
- 失败后支持人工整文档重试；
- Provider retry 和 Docling job identity 只在单次 worker 内存中；
- 进程重启后残留 RUNNING item 会标记失败；
- 不能从 Parse/Embedding 中间状态断点续跑；
- 不具备与 Agent Task 相同的 Lease 恢复和取消语义。

### 实施范围

同步修改中英文 README：

```text
Ingestion 是可追踪的异步任务：保存任务状态和阶段进度，
失败后支持人工整项重试；进程重启不会续跑处理中间状态。

Agent 异步任务另行支持持久状态、Lease、取消和恢复。
```

同时核对 Feature 表、设计原则、运维说明，不再把 Ingestion 与 Agent 的恢复能力合并描述。

### 验收

- 中英文语义一致。
- 与 `docs/domain-boundaries-and-interactions.md` 的当前实现说明一致。
- 不宣称 Ingestion 支持断点续跑、自动恢复或取消。

---

## ANCHR-207I：Agent 状态、依赖和可读性收敛

### 目标

不重写 Agent，不引入通用工作流框架；只让当前已经存在的阶段、验证结果和外部依赖成为明确、不可绕过的代码结构。

### 207I1：恢复可读性

1. 展开 `AgentTaskProcessor` 后半段压缩在单行的状态修改、Repository、Trace 和 publish 逻辑。
2. 用方法和空行显式表达：

```text
准备终态
→ 事务保存 Task + Turn
→ 更新 Run/Trace
→ 发布 Runtime Snapshot
→ 完成 SSE
```

3. 先做格式和等价提取，不同时改变状态机。
4. 不以行数减少作为验收标准。

### 207I2：选择性独立外部依赖协作者

以下对象持有模型 Port、Repository、配置、Trace、监控或对象存储能力时，应成为明确注入的 Application Collaborator，而不是在主 Service 构造函数中手工创建：

- `AgentEvidenceFinalizer`
- `AgentFinalPresentation`
- `RetrievalRerankPolicy`
- 带对象存储签名能力的 Retrieval Result 组装部分
- Ingestion Create/Maintenance/Query 用例

纯算法、无外部依赖、无生命周期状态的 Policy/Parser/Assembler 可以继续手工创建或使用静态方法，例如纯 RRF 计算和简单值映射。禁止为了消除 `new` 把所有小类都注册为 Spring Bean。

事务边界必须跟随真实用例；如果 Ingestion 用例成为 Bean，现有 Facade 上的事务语义必须迁移到用例入口，而不是丢失或形成自调用失效。

### 207I3：显式阶段和结果类型

使用小型阶段枚举和 sealed outcome 表达主循环，不引入第三方状态机框架：

```text
PLANNING
→ TOOL_EXECUTION
→ EVIDENCE_VALIDATION
→ FINALIZING
→ COMPLETED / FAILED
```

建议的结果边界：

```text
模型原始响应
→ ParsedAction
→ ToolCalls / UnverifiedAnswer / ProtocolFailure
→ RegisteredEvidence
→ VerifiedCitedAnswer
→ PresentedAnswer
```

重点是让未验证回答不能直接进入完成路径。不得只把每个 String 包装成没有行为的 Value Object。

### 207I4：统一 Evidence/Citation 约束

1. Evidence 注册、当前 Run 所有权、Marker 对应关系、citation 密度和最终展示形成一条明确流水线。
2. 未注册、历史或伪造 segment ID 不能进入 `VerifiedCitedAnswer`。
3. `Map<String,Object>` 只保留在 Trace/JSON 传输边界；核心验证结果使用明确类型。
4. 207C 先修复字符串误删；207I 不重新实现第二套引用清洗。

### 硬边界

- 不修改 Agent Tool 名称、参数、Prompt 语义、预算、模型调用顺序和 fallback 业务规则。
- 不修改 REST、SSE event、数据库 schema、Agent Task Lease/claim 或前端协议。
- 不引入通用 Command Bus、Event Bus、状态机框架、微服务或新的领域层级。
- 不为了追求“零 if/空值”删除模型、Tool 和 provider 边界上的必要防御。

### 验收

- `AgentWorkflowImpl` 只负责阶段推进和总体编排，不再自己完成协议解析、Evidence 最终模型调用和 Presentation 细节。
- 主循环通过明确 outcome 分支，不靠 null 同时表达“继续、失败、完成”。
- 未验证答案在类型和调用关系上无法直接完成 Run。
- `AgentTaskProcessor` 的终态字段、事务保存、Trace、Runtime Snapshot 和 SSE 顺序可从代码直接阅读。
- 现有 Agent characterization、Tool、citation、budget、fallback、cancel、async task、Trace 和 SSE golden tests 全部保持。
- 新增阶段迁移和非法迁移测试，但不使用 ArchUnit。

---

## 全卡硬边界

1. 任何端点路径、HTTP method、请求 JSON、响应 JSON 或 SSE 字段变化，必须停止并先确认；确认后同步修改 `anchr-web`。
2. 不修改数据库 schema、ES mapping 或索引写入协议，除非对应子卡的决策门禁已经明确批准。
3. 不以“引入 Spring Security”“引入状态机”“所有 Helper 变 Bean”作为形式化验收条件。
4. 不修改与本卡无关的现有工作区变更，特别是当前 README 的未提交修改。
5. 每个子卡独立提交、独立回滚、独立报告测试；不得把 207A–207I 合成一次大重构。
6. 本地测试通过不等于真实 MySQL、Elasticsearch、OSS、Docling、模型服务或生产部署验收。

## 完成定义

每个子卡必须同时满足：

1. 先有能够复现当前问题的失败测试或可核验证据。
2. 修改后目标测试通过。
3. `mvn compile`、`mvn test` 和 `git diff --check` 通过。
4. Docker/Testcontainers 用例明确报告执行或跳过，不能隐藏 skipped。
5. 涉及 Search 契约或前端文案时，`anchr-web` typecheck、测试和 production build 分开报告。
6. 涉及生产配置时，dev/prod 分别启动验证。
7. 涉及 ES 召回时，提供真实 ES 的结果和延迟证据。
8. 明确区分源码完成、本地自动化完成、集成环境完成和部署完成。
9. 只有 A–I 各自验收完成且所有决策门禁关闭后，ANCHR-207 才能标记完成。
