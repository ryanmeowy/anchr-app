# 云能力解耦改造清单

## 目标

- 将所有云厂商能力相关实现收敛到 `integration` 域。
- 其他领域只依赖能力接口（Port），不感知厂商来源。
- 能力切换按能力维度进行，且缺失实现时启动即失败（Fail Fast）。
- 向量索引与 Embedding 模型强绑定，避免人工同步。

## Phase 0：边界止血（先拉直分层）

- [x] 将厂商配置类从 `common` 迁移到 `integration/<capability>/<vendor>/config`。
- [x] 将厂商常量从 `common` 迁移到 `integration`，`common` 禁止出现厂商词。
- [x] 主配置 `application.yaml` 仅保留能力选择器与平台通用配置。
- [x] 索引、向量模型与能力选择器统一放在 `application.yaml`，通过环境变量自由组合能力 provider。
- [x] 删除所有“空实现返回空字符串/空列表”的逻辑，改为明确异常。

## Phase 1：能力统一收敛到 integration

- [x] 统一 Port：`EmbeddingPort`、`RerankPort`、`GenPort`、`OcrPort`、`ObjectStoragePort`、`CredentialIssuePort`。
- [x] 所有 SDK 调用仅允许出现在 `integration` 实现层。
- [x] 应用层与其他域只依赖 Port，禁止直接依赖厂商配置类/SDK。
- [x] 对象存储能力无 fallback；未命中实现时启动失败（Fail Fast）。
- [x] AI 能力无 fallback；未命中实现时启动失败（Fail Fast）。
- [x] 能力选择器按能力维度独立：`embedding/rerank/gen/ocr/object-storage`。

## Phase 2：auth 鉴权解耦

- [x] 在 `auth` 定义 `UploadAuthPolicyPort`（只负责是否允许签发）。
- [x] 在 `integration` 定义 `CredentialIssuePort`（只负责签发厂商凭据）。
- [x] `auth` 调用链固定：先策略校验，再凭据签发。
- [x] `auth` 禁止出现厂商 SDK、签名细节和厂商配置字段。
- [x] 前端直传接口返回统一 DTO，屏蔽厂商字段差异。

## Phase 3：索引与 Embedding 强绑定

- [x] 定义 `VectorProfile = provider + model + dimension + preprocessVersion`。
- [x] 向量索引名称/别名必须自动带 `vectorProfile`。
- [x] 向量缓存 key 必须自动带 `vectorProfile`。
- [x] 启动时校验当前 Embedding 配置与索引 mapping 维度一致，不一致直接失败。
- [x] 模型切换使用“新 profile 新索引 + 回填 + 别名切换”，禁止手工裸切。

## Phase 4：配置与装配治理

- [ ] 统一条件装配键，仅保留一套能力选择器（禁止混用历史键）。
- [ ] 每个能力任一时刻只能装配 1 个实现，冲突时启动失败。
- [ ] 缺能力实现时输出明确错误信息（能力名、选择值、缺失 Bean）。
- [ ] 配置类与实现按能力-厂商分包，避免跨能力耦合。

## Phase 5：质量与可运维

- [ ] 为每个 Port 建立契约测试，所有厂商实现复用同一套测试。
- [ ] 增加启动健康检查：能力实现装配、关键配置完整性、索引维度一致性。
- [ ] 增加能力维度指标：成功率、耗时、错误码、厂商标签。
- [ ] 统一异常映射：integration 内部翻译厂商错误，对上仅暴露平台错误码。

## 验收标准（Definition of Done）

- [ ] `common` 不包含任何厂商配置、厂商常量、厂商 SDK 依赖。
- [ ] `auth` 不包含任何厂商 SDK 依赖，仅通过 Port 使用鉴权能力。
- [ ] 切换任一能力厂商时，不需要改业务代码，仅改配置。
- [ ] Embedding 切换不再依赖人工同步索引名。
- [ ] 任一能力缺失实现时，应用启动阶段直接失败并报出可读原因。
