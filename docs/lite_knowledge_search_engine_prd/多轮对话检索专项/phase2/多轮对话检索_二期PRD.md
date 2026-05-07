# 多轮对话检索专项（二期）PRD

更新时间：2026-05-02  
状态：Draft for Alignment

## 1. 文档目标

本 PRD 基于当前项目已落地能力，定义“二期要新增什么、为什么新增、如何验收”。

二期目标是把当前“可对话检索”升级为“接近 ChatGPT 网页端体验的可验证知识问答产品”：
1. 左侧会话历史 + 底部输入框的对话式 UI。
2. 每轮返回 LLM 摘要回答 + Top3 结果卡片。
3. 卡片可跳转预览页，默认定位主命中片段并支持浏览全文（PDF/TXT/MD，兼容图片）。
4. 保留传统关键词检索入口，与对话检索并列。

## 2. 当前能力盘点（已完成）

### 2.1 后端已有

1. 多轮会话 API 已有：
- `POST /api/conversations`
- `POST /api/conversations/{sessionId}/messages`
- `GET /api/conversations/{sessionId}/messages`

2. 对话链路已打通：rewrite -> 检索 -> LLM 答案生成 -> citations 回传。

3. 文本入库链路已支持 `PDF/TXT/MD`，并写入统一 `kb_segment` 索引。

4. 对象存储层已有短期签名下载 URL 生成能力（可复用做预览签发）。

### 2.2 前端已有

1. Streamlit 已有 Conversation Search 页面（验证链路可用）。
2. 已能展示回答与 citations，但 citation 还是静态文本，不支持点击跳转预览。

### 2.3 当前缺口

1. 缺少真正的 Web 聊天 UI（左侧历史、底部输入、消息体验）。
2. 缺少“每轮 Top3 卡片”的稳定协议与展示。
3. 缺少统一预览服务与预览页（定位 + 全文浏览）。
4. 缺少点击行为与预览链路的观测指标。
5. 缺少会话历史列表 API，左侧历史无法稳定跨刷新/跨设备恢复。
6. 缺少按 `segmentId` 稳定查询 segment 元数据的能力，预览链路不能依赖入库期临时缓存。
7. 预览加载策略未定稿，PDF/TXT/MD 直接加载对象存储 URL 可能受 CORS、Range Request 与签名过期影响。
8. React/Next.js 正式版缺少 API Client、鉴权、401 处理与环境配置约束。
9. 图片 OCR bbox 入库与查询能力尚未形成闭环，图片预览定位需要在 Phase2 主链路前补齐。

## 3. 产品范围

### 3.1 In Scope（本期必须做）

1. 新 Web 前端 IA：一级菜单包含 `关键词检索` 与 `对话检索`。
2. 对话检索页面提供 ChatGPT 风格基础体验：
- 左侧会话列表
- 中间消息流
- 底部固定输入框

3. 每轮回答返回：
- LLM 摘要回答（已有能力复用）
- Top3 结果卡片（每卡 = 1 个 asset，卡片内必须包含 `primaryHit` segment）

4. 卡片点击跳转预览页：
- 文本：PDF/TXT/MD
- 图片：原图预览
- 支持定位命中片段（页码/chunk/bbox）
- 支持继续浏览完整内容

5. 安全预览链接：短期有效，不返回永久裸链。

6. 埋点与指标：发送成功率、首包耗时、卡片点击率、预览成功率。
7. 会话历史列表 API：支持左侧最近会话列表刷新恢复。
8. Asset 级 Top3 卡片协议：一张卡片对应一个 asset，但必须包含可定位的 `primaryHit` segment。
9. 预览加载策略定稿：明确直接签名 URL 或后端受控内容代理的适用边界。
10. 摘要失败与无证据兜底：LLM 超时、无证据、引用不一致时有明确降级。
11. 预览响应支持 `surroundingChunks`：用于 TXT/MD 上下文展示、snippet 定位失败兜底和用户理解命中上下文。
12. 图片 OCR bbox 入库与查询能力：作为 Phase2 前置能力，详见《多轮对话检索_二期_bbox前置PRD.md》；bbox 缺失或无效时仍需安全降级，不绘制错误框。

### 3.1.1 P1 增强范围（建议本期做，允许不阻断 MVP）

1. 会话删除/重命名/归档中的最小集合：优先支持删除，其次支持重命名。
2. LLM 推荐追问：基于同一批 grounding context 生成 2-4 条追问；默认先复用现有 `suggestedQuestions` 模板能力。
3. LLM 自动会话标题：基于首轮 query/answer/grounding context 生成更自然的标题；默认先保留现有 deterministic 标题。
4. 开发者模式开关：用于展示 retrieval trace、rewrite、preferredModalities、候选 segment 等调试信息。
5. 回答反馈机制：提供“有帮助/没帮助”两个按钮，用于后续评测和质量改进。
6. 会话持久化增强：如产品要求长期保存、审计或跨设备长期历史，再引入 DB 表；二期不作为 P0 强依赖。

### 3.2 Out of Scope（本期不做）

1. 语音输入与语音播报。
2. 多人实时协作会话。
3. 文档高亮标注编辑（只做只读预览+定位）。
4. 复杂权限体系重构（沿用现有认证体系）。
5. Agent 工具调用编排。
6. 长期记忆、用户偏好画像、跨会话自动记忆。
7. 对文档做复杂编辑、批注、协同标注。
8. 为会话历史专门引入新的 DB 持久化体系（除非排期内明确升级为长期历史需求）。
9. 首次进入对话检索页的新手引导。
10. Playwright 自动化 E2E。
11. 预览页上一处/下一处命中导航。
12. 合规级日志脱敏专项。

### 3.3 前端实现策略（双轨）

1. `MVP（功能验证）`：基于现有 Streamlit 页面实现输入、回答、resultCards 基础展示。
2. `正式版（产品体验）`：采用 React/Next.js 实现 ChatGPT 风格交互（左侧历史、底部输入、路由化预览页、细粒度交互状态）。

说明：
1. Streamlit 只用于验证后端响应与基础展示，不承担卡片 UX、预览页、命中定位能力。
2. 二期交付口径：先保证 MVP 闭环可用，再推进正式版体验升级。
3. 正式体验以 React/Next.js 为准。

## 4. 信息架构与命名建议

### 4.1 一级菜单（建议定稿）

1. `关键词检索`（传统检索）
2. `对话检索`（LLM 检索问答）

说明：
- 不建议叫“LLM搜索”，对业务用户语义偏技术化。
- `对话检索`更符合行为心智，也和现有专项名称一致。

### 4.2 对话检索页面结构

1. 左栏：会话历史
- 新建会话
- 会话标题（支持自动标题）
- 最近会话列表

2. 主区：消息流
- 用户消息
- 助手回答
- 回答下方 Top3 卡片

3. 底部：输入区
- 多行输入框
- 发送按钮
- 发送中状态

## 5. 核心用户故事

1. 作为用户，我可以像 ChatGPT 一样连续提问，并在左侧看到历史会话。
2. 作为用户，我每次提问后，除了摘要回答，还能看到最相关的 3 条结果卡片。
3. 作为用户，我点击卡片后能打开预览页并直接定位命中处，但也能浏览完整文档。
4. 作为用户，我仍可以切回关键词检索，进行传统结果列表检索。

## 6. 功能需求

### FR-1 对话检索 UI（Chat 风格）

1. 提供会话列表、消息流、底部输入。
2. 支持会话创建、切换、加载历史消息。
3. 每轮消息展示：`query`、`answer`、`createdAt`。
4. 支持从后端读取最近会话列表，刷新页面后左侧历史可在 TTL 内恢复。
5. P1：支持会话删除；重命名可手动或沿用自动标题。
6. 会话历史必须具备用户维度：列表、删除、重命名只能作用于当前用户可访问的会话。
7. 存储策略：
- 二期优先沿用现有 Redis 会话存储，补充 `userId -> sessionId` 索引与删除/重命名能力。
- `ConversationSession` 预留 `userId` 字段；当前无 SSO 时可使用 `default`、`anonymous` 或 `X-Access-Token` hash 作为轻量 user key。
- 不为了删除/重命名单独引入 DB。
- 若要求长期保存、审计查询或超过 Redis TTL 的稳定历史，再单独立项引入 DB。

验收：
1. 新建会话后可立即发送消息。
2. 刷新页面后可在会话 TTL 内恢复最近会话列表与历史消息。
3. 左侧历史不依赖浏览器本地缓存作为唯一数据源。
4. 用户 A 不可看到或操作用户 B 的会话。

### FR-2 回答 + Top3 卡片

1. 每轮回答继续使用现有 LLM 生成答案能力。
2. 新增 `resultCards`（Top3）返回结构，每项对应 1 个 asset。
3. 每张卡片必须包含 `primaryHit`，`primaryHit` 对应 1 个最相关 segment，用于摘要引用回溯与点击预览定位。
4. 卡片字段至少包含：
- `assetId`
- `assetType`
- `title/fileName`
- `score`
- `hitCount`
- `primaryHit`
- `additionalHits`（可选，展示同 asset 下其他相关片段）
5. Top3 选择规则：
- Top3 展示维度必须是 asset。
- 每个 asset 卡片选择该 asset 下最高相关性的 segment 作为 `primaryHit`。
- 同 asset 下其他高相关 segment 进入 `additionalHits`，不额外占用 Top3 卡片位。
- 点击卡片默认跳转 `primaryHit.segmentId` 对应预览位置。
6. Top3 去重与聚合策略：
- 按 segment 候选相关性排序后，先按 `assetId` 聚合。
- 每个 asset 的分数固定取 `primaryHit.score`，本期不引入同 asset 多命中加权。
- Top3 默认取前 3 个 asset。
- 候选 asset 少于 3 个时按实际数量返回。
- 卡片内可显示“同文档另有 N 个相关片段”，`additionalHits` 默认最多返回 2 条。

验收：
1. 每轮最多展示 3 张卡片。
2. 每张卡片对应唯一 asset。
3. 每张卡片必须包含 `primaryHit.segmentId`。
4. 卡片点击后必须能通过 `primaryHit.segmentId` 找到预览所需元数据。
5. 同一 asset 的多个命中不应占用多张 Top3 卡片。
6. `resultCards` 必须保存到 `ConversationTurn.resultCardsJson`，历史回放不二次检索。

### FR-2A 摘要总结规范（本期必须）

1. 摘要总结使用现有对话答案生成链路，输出字段沿用 `answer`（不新增并行摘要字段）。
2. 摘要必须“有证据约束”：
- 仅可基于当轮检索命中的证据片段生成
- 禁止引入证据中不存在的新事实
3. 摘要建议输出结构：
- 先给 1-2 句结论
- 再给 2-4 条要点（短句）
- 末尾附“参考来源”编号（如 `[1][2]`）
4. 摘要与 Top3 卡片的一致性要求：
- 摘要中的引用编号必须能映射到当轮返回卡片中的 `primaryHit` 或 `additionalHits`
- 若证据不足，返回“未找到足够内容支持该问题”的受控兜底文案
5. 摘要失败降级：
- LLM 超时/异常：展示 Top3 卡片，并提示“摘要生成失败，可查看下方检索结果”
- 无证据：不输出臆测结论，仅展示无证据兜底文案
- 部分证据：摘要必须只基于可回溯 segment 生成

验收：
1. 80% 以上样例可生成“结论 + 要点 + 引用”的结构化摘要。
2. 100% 摘要引用可回溯到当轮证据（`primaryHit/additionalHits.segmentId` 可对齐）。
3. 无证据场景必须触发兜底文案，不输出臆测结论。

### FR-3 卡片跳转预览

1. 点击卡片打开预览页（新路由）。
2. 预览页可读取 `assetId/segmentId` 并拉取预览信息。
3. 定位规则：
- PDF：定位页码（`pageNo`）
- TXT/MD：定位 chunk 或关键词片段
- 图片：基于 OCR bbox 定位命中区域，bbox 缺失或无效时展示原图与命中文本并记录数据质量问题
4. 预览服务必须通过 `segmentId` 稳定解析 asset 与 anchor 信息，不依赖入库任务临时缓存。
5. PDF/TXT/MD 加载方式必须在实现前完成验证：
- 直接签名 URL：必须验证 CORS、Range Request、TXT/MD fetch 可用
- 后端代理/内容 API：必须控制权限、过期与响应体大小
6. 预览响应必须支持 `surroundingChunks`：
- 默认返回当前 chunk 及相邻前后各 1 个 chunk。
- 每个 chunk 内容需要限制长度，避免预览接口响应过大。
- 用于 TXT/MD 上下文展示、snippet 定位失败兜底、用户理解命中位置。

验收：
1. 90% 以上样例可定位到正确命中区域或相邻位置。
2. 预览页可继续翻页/滚动浏览全文。
3. 超过入库临时缓存 TTL 后，已索引 segment 仍可打开预览。
4. TXT/MD 预览在有 `surroundingChunks` 时能展示命中上下文；没有相邻 chunk 时不报错。

### FR-3A 预览页 Hit 定位实现方案（本期定稿）

1. 总体原则：先“稳定定位”，后“精准高亮”。
2. 入口主键：卡片点击仅依赖 `segmentId`（可附带 `assetId` 作为冗余）。
3. 预览页初始化：
- 调用 `GET /api/v1/preview/segments/{segmentId}`
- 获取 `previewUrl/previewType/anchor/snippet/expiresAt`

4. 分类型定位策略（MVP）：
- `PDF`：
  - 第一步：按 `anchor.pageNo` 跳页（硬定位）
  - 第二步：在该页 text layer 用 `snippet` 做首个命中高亮
  - 失败回退：保留页级定位并提示“片段未精确命中”
- `TXT/MD`：
  - 加载全文后按 `snippet` 首个命中滚动定位
  - 若未命中，则优先用 `surroundingChunks.current` 定位，再按 `anchor.chunkOrder` 近似定位
- `IMAGE`：
  - Phase2 启动前必须补齐图片 OCR bbox 入库与查询能力
  - 默认基于有效 `anchor.bbox` 在原图上绘制命中框
  - 若 `bbox` 缺失、越界、比例异常或无法映射到渲染尺寸，忽略 bbox 并展示 OCR/命中文本提示
  - bbox 缺失应记录为数据质量问题，不作为图片主路径的正常状态

5. 全文浏览要求：
- 定位后用户仍可自由翻页、滚动、缩放，不锁死在命中片段。

6. 失败与过期兜底：
- 定位失败：提示“已定位到相邻位置”，并提供“文内搜索 snippet”操作
- `previewUrl` 过期：自动刷新一次预览地址并重试

7. 增强方案（非本期 Must）：
- 后端返回稳定 `hitRanges`（如 `charStart/charEnd` 或页内 offset）
- 前端从“模糊片段匹配”升级为“确定性坐标高亮”
验收：
1. `PDF` 页级定位成功率 >= 98%。
2. `TXT/MD` 片段或近似定位成功率 >= 90%。
3. `IMAGE` 有效 bbox 样例可在原图上绘制命中框；bbox 缺失或无效时必须安全降级，不画错框。
4. 预览链接过期场景自动恢复成功率 >= 90%（单次自动重试）。

### FR-4 保留关键词检索入口

1. 新前端保留传统关键词检索页面入口。
2. 两种检索入口并列，不互相替代。

验收：
1. 关键词检索与对话检索均可独立访问。
2. 不影响现有关键词检索主流程可用性。

### FR-5 可观测性

1. 事件埋点：
- `conversation_send`
- `conversation_answer_render`
- `top3_card_click`
- `preview_open_success/fail`

2. 关键指标：
- 对话请求成功率
- P95 首次回答耗时
- 卡片点击率
- 预览打开成功率

3. 日志与隐私要求：
- 不记录完整签名 URL
- 不记录 token
- 不记录大段原文内容
- 不做合规级日志脱敏专项

## 7. 接口与数据协议增量

### 7.1 对话消息响应扩展

在 `POST /api/conversations/{sessionId}/messages` 响应中新增：
1. `resultCards: ResultCardDTO[]`（最多 3 条）
2. `previewToken` 或 `previewRef`（可选，若走后端预签发）

说明：
1. `answer` 字段即“摘要总结”承载字段，二期不新增 `summary` 平行字段，避免协议冗余与前后端双轨维护。
2. `resultCards` 是展示层协议，卡片维度是 asset；精确定位由卡片内 `primaryHit.segmentId` 承担。

`ResultCardDTO` 建议：
1. `assetId`
2. `assetType`
3. `fileName`
4. `title`
5. `score`
6. `hitCount`
7. `primaryHit`
8. `additionalHits`

`ResultHitDTO` 建议：
1. `segmentId`
2. `snippet`
3. `score`
4. `pageNo`
5. `anchor`
6. `hitType`
7. `sourceRef`（仅后端内部可用也可）

### 7.2 新增预览信息接口

建议新增：`GET /api/v1/preview/segments/{segmentId}`

返回：
1. `assetId`
2. `assetType`
3. `fileName`
4. `previewUrl`（短期签名）
5. `previewType`（`PDF/TXT/MD/IMAGE`）
6. `anchor`（页码/chunk/bbox）
7. `expiresAt`
8. `snippet`
9. `surroundingChunks`

`surroundingChunks` 建议结构：
1. `segmentId`
2. `chunkOrder`
3. `pageNo`
4. `content`
5. `relation`（`previous/current/next`）

约束：
1. 默认最多返回 3 个 chunk：`previous/current/next`。
2. 单个 chunk 内容默认截断到 2KB-4KB，避免接口响应过大。
3. 若前后 chunk 不存在，仅返回 `current`。
4. 用户无权限访问 asset 时，不返回任何 chunk 内容。

说明：
- 优先通过 `segmentId` 解析 `sourceRef` 并签发 URL，减少对短 TTL 元数据的依赖。
- 如需补充可加 `GET /api/v1/preview/assets/{assetId}`。
- `surroundingChunks` 查询需要支持按当前 segment 查前后相邻 chunk，建议能力：
  - `findBySegmentId(segmentId)`
  - `findNeighborChunks(assetId, pageNo, chunkOrder, window)`
  - 默认 `window=1`
- 若当前 segment 缺少 `chunkOrder` 或相邻 chunk 不存在，仅返回 `current`。

### 7.3 新增会话历史列表接口

建议新增：`GET /api/conversations?limit=20&cursor={cursor}`

返回：
1. `items[].sessionId`
2. `items[].userId`
3. `items[].title`
4. `items[].createdAt`
5. `items[].updatedAt`
6. `items[].lastMessagePreview`
7. `items[].expiresAt`
8. `nextCursor`

说明：
1. 用于左侧会话历史列表。
2. 列表按 `updatedAt desc` 排序。
3. 前端可以做本地缓存优化，但不能把本地缓存作为唯一历史来源。
4. 二期优先沿用 Redis 存储，需要补充当前用户的 session 索引。
5. 如果当前认证上下文暂未提供稳定 `userId`，需要先定义匿名/默认用户策略，避免所有用户共享同一会话历史。
6. 当前无 SSO 时，允许使用 `X-Access-Token` hash 作为轻量 user key；未来接 SSO 后替换为真实 `userId`。

P1 可选接口：
1. `PATCH /api/conversations/{sessionId}`：重命名会话。
2. `DELETE /api/conversations/{sessionId}`：删除或归档会话。

存储策略：
1. P0/P1 均不强制引入 DB。
2. Redis 方案需要支持：
- `sessionId -> session`
- `sessionId -> turns`
- `userId -> sessionId sorted set`
- 逻辑删除或物理删除
3. 触发 DB 改造的条件：
- 会话需要长期保存超过 Redis TTL
- 需要复杂分页、审计、检索或运营分析
- 需要强一致跨实例历史管理

### 7.4 预览内容加载策略

实现前必须在以下方案中定稿一种主路径：

1. 直接签名 URL：
- 适用：对象存储支持前端跨域访问、PDF Range Request、TXT/MD fetch
- 优点：后端压力小
- 风险：CORS/Range 配置复杂，签名 URL 泄露面更大

2. 后端受控代理/内容 API：
- 适用：需要统一鉴权、日志可控、规避对象存储 CORS 差异
- 优点：权限边界清晰，前端加载稳定
- 风险：后端带宽与大文件响应压力更高

本期建议：
1. PDF 优先验证直接签名 URL + PDF.js。
2. TXT/MD 若 CORS 不稳定，优先走后端受控内容 API。
3. 不在日志中打印完整 `previewUrl`。
4. 预览接口必须接入轻量鉴权：
- 请求头使用现有 `X-Access-Token` 或等价 token
- 后端校验 token 后才签发 `previewUrl`
- token 可用于计算 `userKey/tokenHash`，参与会话归属和 preview cache key
5. 签名 URL 泄露处理：
- 直接 OSS 签名 URL 一旦泄露，后端无法中途撤销单个 URL
- 必须使用短 TTL（默认 5-15 分钟）
- 禁止日志打印完整 URL
- 必要时切换后端受控代理/内容 API，以便后端持续鉴权
6. 预览 URL 防抖/去重：
- 前端同一 `segmentId` 连续点击只发起一次预览请求
- 前端在 `expiresAt` 前可复用已有预览响应
- 后端可按 `preview:segment:{segmentId}:token:{tokenHash}` 缓存 preview response
- 后端缓存 TTL 取 `previewUrl` 剩余有效期减去安全 buffer（建议 30 秒）

## 8. 技术实现原则（结合现有能力）

1. 复用现有会话后端，不重写对话主链路。
2. Top3 卡片来源必须复用当轮 `retrievalResult.topCandidates`，按 asset 聚合生成 `resultCards`，禁止为了生成或回放卡片发起二次检索。
3. 预览签发复用对象存储服务已有 `buildDownloadUrl` 能力。
4. 新能力尽量做“增量接口扩展”，减少回归风险。
5. 预览链路通过 `segmentId` 查询稳定索引或持久化元数据，不依赖入库任务短期 Redis 缓存。
6. Top3 协议以 asset 卡片为准；每张卡片必须保留 `primaryHit.segmentId` 作为定位与证据回溯入口。
7. 正式前端所有后端请求统一走 API Client，避免鉴权、错误处理和 baseURL 分散。
8. Top3 选择优先保证 asset 多样性，同时不丢失 segment 级命中信息。
9. 会话历史二期优先补 Redis 用户索引和操作能力，不把 DB 作为删除/重命名的前置依赖。
10. `surroundingChunks` 只返回受控上下文，不承担全文预览职责。
11. `ConversationTurn` 需要新增 `resultCardsJson` 字段；`resultCards` 必须随 turn 持久化，历史消息直接读取已保存的 `resultCards`。
12. `retrievalTraceJson` 用于开发者调试和审计，不作为历史卡片重建的唯一数据源。
13. `preferredModalities` 一期已有基础能力，二期不作为新增功能，仅在开发者模式中展示其 rewrite 输出和生效情况。
14. 图片 OCR bbox 入库、查询、DTO 透传和前端映射作为 Phase2 前置能力；图片预览主路径应基于 bbox 定位，降级仅处理异常数据。
15. 预览签发必须支持防抖/去重，避免同一 segment 在短时间内重复签发 URL。

## 8A. 工程要求

1. 正式版前端必须提供统一 API Client。
2. 支持环境化 API baseURL 配置。
3. 支持现有认证体系下的 token/header 注入。
4. 统一处理 `401/403/404/5xx`：
- `401`：提示登录失效或跳转登录
- `403`：提示无权限
- `404`：提示会话/segment/asset 不存在
- `5xx`：提示服务异常并保留重试入口
5. 提供开发者模式开关：
- 默认关闭
- 开启后展示 `retrievalTrace`、query rewrite、`preferredModalities`、候选 segment 数量、answer fallback 信息
- 不影响普通用户主流程
6. 预览接口必须使用现有轻量 token 鉴权：
- 优先复用 `@RequireAuth` / `X-Access-Token`
- token 不写入日志
- 前端请求预览接口必须带 token/header

验收：
1. 对话、会话历史、关键词检索、预览页都通过统一 API Client 请求后端。
2. 登录失效、无权限、资源不存在均有明确错误态，不出现空白页。
3. 开发者模式关闭时，不展示 trace/debug 信息。

## 9. 非功能要求

1. 对话页面首屏可交互时间：P95 < 2.5s（前端加载）。
2. 单轮问答接口耗时：P95 < 6s（不含超长模型抖动）。
3. 预览接口耗时：P95 < 800ms（不含文件下载本身）。
4. 预览链接有效期建议：5-15 分钟。

## 10. 验收标准

1. 菜单包含 `关键词检索` 与 `对话检索`，均可正常使用。
2. 对话检索页面具备左侧历史 + 底部输入的完整体验。
3. 每轮回答展示 Top3 卡片，且每卡对应唯一 asset，并包含 `primaryHit.segmentId`。
4. 点击卡片可打开预览页并完成命中定位。
5. 预览页可浏览完整文档内容（不仅命中片段）。
6. 指标面板可看到点击与预览成功率。
7. 左侧历史来自后端会话列表 API，刷新后可在 TTL 内恢复。
8. Top3 卡片均为 asset 级结果，不出现同一 asset 默认占满 Top3 的情况。
9. TXT/MD/PDF 预览加载策略已验证 CORS、Range Request 或后端代理路径。
10. 鉴权失效、无权限、资源不存在、摘要失败均有明确降级体验。
11. Top3 在多 asset 候选充足时优先覆盖不同 asset。
12. 预览响应支持 `surroundingChunks`，TXT/MD 可展示当前命中上下文。
13. 图片 OCR bbox 前置能力已完成；有效 bbox 可绘制命中框，bbox 缺失或无效时安全降级，不绘制错误命中框。
14. `resultCards` 由当轮检索候选生成并随 turn 保存，历史回放不二次检索。
15. `ConversationTurn.resultCardsJson` 与 `ConversationSession.userId` 字段完成兼容改造。
16. 预览接口接入轻量 token 鉴权，且同一 `segmentId+tokenHash` 的预览 URL 可在有效期内复用。

## 11. 建议与取舍

### 11.1 建议本期不要做

1. 不做流式打字机效果（可后续优化，不阻断主价值）。
2. 不做复杂“引用多选插入上下文”。
3. 不做前端离线缓存全文。
4. 不做复杂长期记忆和用户偏好画像。
5. 不在 Streamlit 中投入完整产品级预览体验，Streamlit 只作为 MVP 基础展示。
6. 不为会话删除/重命名单独引入 DB；DB 持久化作为长期历史需求的后续专项。
7. 不做首次进入对话检索页的新手引导。
8. 不做 Playwright 自动化 E2E。
9. 不做上一处/下一处命中导航。
10. 不做合规级日志脱敏专项。

### 11.2 建议补充但成本低的能力

1. P1：卡片支持“复制引用”按钮（提升可用性）。
2. 预览失败时提供“重新获取链接”按钮。
3. Top3 卡片增加 `hitType` 徽标（TEXT_CHUNK/IMAGE_OCR_BLOCK/IMAGE_CAPTION）。
4. P1：支持会话删除，重命名可后续增强。
5. 预览页展示 `surroundingChunks`，帮助用户理解命中上下文。
6. P1：LLM 推荐追问。
7. P1：LLM 自动会话标题。
8. P1：回答反馈按钮。
9. P1：开发者模式开关。

## 12. 里程碑与排期（建议）

### M1（4-5 天）

1. 对话响应扩展 `resultCards`。
2. 预览接口 `GET /api/v1/preview/segments/{segmentId}`。
3. 预览签发与权限校验打通。
4. 会话历史列表 API。
5. Asset 级 Top3 卡片选择规则与 `primaryHit.segmentId` 查询能力。
6. 预览加载策略验证与定稿。
7. Top3 asset 多样性去重规则。
8. `surroundingChunks` 预览协议。
9. `ConversationTurn.resultCardsJson`、`ConversationSession.userId` 字段兼容改造。
10. 预览接口轻量 token 鉴权、URL 去重复用和签名 URL 泄露边界说明。

### M2（4-6 天）

1. Streamlit MVP：输入、回答、resultCards 基础展示。
2. React/Next.js 正式版骨架：左侧会话 + 底部输入 + 消息流（至少完成静态路由与接口联调）。
3. React/Next.js API Client、鉴权、baseURL、401/403/404 错误态。
4. React 预览页支持图片 bbox 命中框定位，并具备无效 bbox 安全降级。

### M3（2-3 天）

1. React/Next.js 正式版完善：卡片交互、失败态、加载态、预览页联动。
2. 埋点、观测、错误处理。
3. 回归测试与验收脚本。
4. 文档更新（接口、前端交互说明）。
5. P1：会话删除/重命名、复制引用、LLM 追问、LLM 自动标题、回答反馈、开发者模式按排期选择落地。

总计：约 12-17 人天。

## 13. 风险与应对

1. 风险：文本类定位精度不稳定（尤其 TXT/MD）。
- 应对：先保证页级/chunk级定位，再逐步增强高亮算法。

2. 风险：预览 URL 过期导致打开失败。
- 应对：前端自动重试一次“刷新预览地址”。

3. 风险：Top3 与答案引用不一致造成用户困惑。
- 应对：Top3 直接复用同一批候选，卡片保留 `primaryHit/additionalHits.segmentId` 对齐校验。

4. 风险：左侧历史只存在前端本地缓存，刷新或换设备后丢失。
- 应对：新增后端会话列表 API，本地缓存仅作为性能优化。

5. 风险：TXT/MD/PDF 直接加载对象存储 URL 受 CORS 或 Range Request 影响。
- 应对：实现前验证直接签名 URL；失败时切换到后端受控代理/内容 API。

6. 风险：预览依赖入库期临时 Redis metadata，缓存过期后无法打开旧结果。
- 应对：预览接口通过 `segmentId` 查询稳定索引或持久化元数据。

7. 风险：同一 asset 多个高分 segment 占满 Top3，用户看到的来源单一。
- 应对：Top3 先按 asset 聚合，同一 asset 的多个命中进入 `additionalHits`，不额外占用卡片位。

8. 风险：为会话删除/重命名引入 DB 导致二期范围扩大。
- 应对：二期先基于 Redis 增加用户索引和操作能力；长期历史另立 DB 持久化专项。

9. 风险：`surroundingChunks` 响应过大或泄露无权限内容。
- 应对：默认只返回前后各 1 个 chunk，单 chunk 限长，并在权限校验后返回。

10. 风险：bbox 入库能力未先补齐，导致图片预览只能降级为原图展示。
- 应对：新增《多轮对话检索_二期_bbox前置PRD.md》，在 Phase2 主链路前完成 bbox 协议、入库、查询、preview API 透传和前端映射；异常 bbox 仍需安全降级。

11. 风险：历史卡片回放为了展示 Top3 触发二次检索，导致结果漂移。
- 应对：`resultCards` 由当轮 `retrievalResult.topCandidates` 生成并随 turn 持久化，历史回放直接读取保存结果。
