# 全局唯一 Embedding Provider 与双索引链路保留任务卡

## 总体说明

本任务集对应“embedding 能力全局唯一 provider，底层 backend 可配置，双索引链路暂时保留”的方案。

核心约束：

- 不下线 `smart_gallery_*`。
- 不合并 `smart_gallery_*` 与 `kb_segment_*`。
- 不迁移 `/api/v1/vision/*` 到 `UnifiedSearchService`。
- 不改变现有 REST API contract。
- embedding backend 不再由 local/cloud Spring profile 隐式决定。
- 全局只允许一个业务侧 embedding provider Bean。

## 任务列表

| ID | 标题 | 优先级 | 依赖 | 预估 | 状态 |
| --- | --- | --- | --- | --- | --- |
| UEP-01 | 明确全局 embedding backend 与配置契约 | P0 | 无 | 0.5 天 | TODO |
| UEP-02 | 新增 `EmbeddingProperties` | P0 | UEP-01 | 0.5 天 | TODO |
| UEP-03 | 新增 `EmbeddingBackend` 接口与 backend 选择机制 | P0 | UEP-02 | 0.5-1 天 | TODO |
| UEP-04 | 新增 `UnifiedEmbeddingProvider` | P0 | UEP-03 | 0.5-1 天 | TODO |
| UEP-05 | 改造阿里云 embedding 实现为 backend | P0 | UEP-03, UEP-04 | 0.5-1 天 | TODO |
| UEP-06 | 改造火山引擎 embedding 实现为 backend | P0 | UEP-03, UEP-04 | 0.5-1 天 | TODO |
| UEP-07 | 改造本地 embedding 实现为 backend | P0 | UEP-03, UEP-04 | 0.5-1 天 | TODO |
| UEP-08 | 移除 embedding 对 `app.capability-provider.embedding` 的依赖 | P0 | UEP-05, UEP-06, UEP-07 | 0.5 天 | TODO |
| UEP-09 | 调整 vector profile 使用 `app.embedding.backend` | P0 | UEP-02 | 0.5 天 | TODO |
| UEP-10 | 统一 local/cloud 配置与索引版本 | P0 | UEP-08, UEP-09 | 0.5-1 天 | TODO |
| UEP-11 | 增加启动校验与单元测试 | P0 | UEP-04, UEP-09 | 1 天 | TODO |
| UEP-12 | 扩展 ES CLI 默认 allowlist 覆盖双索引 | P1 | UEP-10 | 0.5 天 | TODO |
| UEP-13 | 小批量双链路验证与阈值校准 | P1 | UEP-10, UEP-11 | 1-2 天 | TODO |
| UEP-14 | 全量回填与 alias 切换 runbook | P1 | UEP-13 | 1-2 天 | TODO |

## UEP-01：明确全局 embedding backend 与配置契约

### 背景

本方案的前提是 embedding 不再跟随 local/cloud profile 切换，而是由全局配置统一决定。

### 工作内容

- 确认默认 backend：`aliyun`、`volcengine` 或 `local`。
- 确认默认 model。
- 确认默认 dimension。
- 确认默认 preprocess-version。
- 确认默认 image-input-mode。
- 确认本地开发和云端部署是否都能访问默认 backend。
- 明确 backend 切换方式，例如环境变量：

```bash
APP_EMBEDDING_BACKEND=aliyun
APP_EMBEDDING_MODEL=multimodal-embedding-v1
APP_EMBEDDING_DIMENSION=1024
APP_EMBEDDING_PREPROCESS_VERSION=v1
```

### 产出

- embedding 配置契约。
- 默认 backend 决策。
- 模型、维度、预处理版本说明。

### 验收标准

- 明确只有 `app.embedding.backend` 决定 embedding backend。
- 明确 `app.capability-provider.embedding` 不再使用。
- 明确 local/cloud profile 不再覆盖 embedding backend。

## UEP-02：新增 `EmbeddingProperties`

### 背景

当前 embedding 配置散落在 `@Value` 和 profile 文件中，需要统一配置入口。

### 工作内容

- 新增配置类：

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {
    private String backend;
    private String model;
    private Integer dimension;
    private String preprocessVersion = "v1";
    private String imageInputMode = "auto";
}
```

- 支持 relaxed binding：
  - `preprocess-version` -> `preprocessVersion`
  - `image-input-mode` -> `imageInputMode`

### 涉及文件

- 新增：`src/main/java/com/smart/vision/core/common/config/EmbeddingProperties.java`

### 验收标准

- 应用能绑定 `app.embedding.backend/model/dimension/preprocess-version/image-input-mode`。
- 缺少关键字段时后续校验能给出明确错误。

## UEP-03：新增 `EmbeddingBackend` 接口与 backend 选择机制

### 背景

厂商实现需要从业务端口实现类改为统一 provider 的内部 backend。

### 工作内容

- 新增接口：

```java
public interface EmbeddingBackend {
    String backendName();
    List<Float> embedText(String text);
    List<Float> embedImage(String imageInput);
    List<Float> embedImage(byte[] imageBytes, String contentType);
}
```

- 可选新增 `EmbeddingBackendRegistry`：
  - 收集所有 `EmbeddingBackend`。
  - 按 `backendName()` 建立索引。
  - 未找到配置 backend 时 fail fast。
  - backendName 重复时 fail fast。

### 涉及文件

- 新增：`src/main/java/com/smart/vision/core/integration/multimodal/embedding/EmbeddingBackend.java`
- 可选新增：`EmbeddingBackendRegistry.java`

### 验收标准

- backend 可按名称选择。
- 未配置或配置不存在的 backend 有明确错误信息。
- 重复 backendName 有明确错误信息。

## UEP-04：新增 `UnifiedEmbeddingProvider`

### 背景

业务侧只能看到一个 embedding provider，避免多个厂商 Bean 直接实现业务端口。

### 工作内容

- 新增 `UnifiedEmbeddingProvider implements SearchEmbeddingPort, IngestionEmbeddingPort`。
- 所有方法委托给 `app.embedding.backend` 选中的 `EmbeddingBackend`。
- 对空向量或异常做统一错误处理。

### 涉及文件

- 新增：`src/main/java/com/smart/vision/core/integration/multimodal/embedding/UnifiedEmbeddingProvider.java`

### 验收标准

- Spring 容器中只有一个 `SearchEmbeddingPort` Bean。
- Spring 容器中只有一个 `IngestionEmbeddingPort` Bean。
- `embedText`、`embedImage(url)`、`embedImage(bytes)` 都能委托到选中 backend。

## UEP-05：改造阿里云 embedding 实现为 backend

### 背景

阿里云实现当前直接实现业务端口，并通过 `app.capability-provider.embedding=aliyun` 装配。

### 工作内容

- 将 `AliyunMultiModelEmbeddingService` 改为 `EmbeddingBackend`，或拆出 `AliyunEmbeddingBackend`。
- `backendName()` 返回 `aliyun`。
- 保留现有 `BailianEmbeddingManager` 调用逻辑。
- 移除对 `SearchEmbeddingPort`、`IngestionEmbeddingPort` 的直接实现。
- 移除 embedding 维度上的 `@ConditionalOnProperty(prefix = "app.capability-provider", name = "embedding", ...)`。

### 涉及文件

- `src/main/java/com/smart/vision/core/integration/multimodal/service/cloud/aliyun/AliyunMultiModelEmbeddingService.java`
- 或新增 `src/main/java/com/smart/vision/core/integration/multimodal/embedding/AliyunEmbeddingBackend.java`

### 验收标准

- `app.embedding.backend=aliyun` 时实际调用阿里云 backend。
- 阿里云 backend 不再作为业务端口 Bean 暴露。

## UEP-06：改造火山引擎 embedding 实现为 backend

### 背景

火山引擎实现当前直接实现业务端口，并通过 provider 条件装配。

### 工作内容

- 将 `VolcengineEmbeddingService` 改为 `EmbeddingBackend`，或拆出 `VolcengineEmbeddingBackend`。
- `backendName()` 返回 `volcengine`。
- 保留现有 `VolcengineEmbeddingManager` 调用逻辑。
- 移除对业务端口的直接实现。
- 移除 embedding provider 条件装配。

### 涉及文件

- `src/main/java/com/smart/vision/core/integration/multimodal/service/cloud/volcengine/VolcengineEmbeddingService.java`
- 或新增 `VolcengineEmbeddingBackend.java`

### 验收标准

- `app.embedding.backend=volcengine` 时实际调用火山 backend。
- 火山 backend 不再作为业务端口 Bean 暴露。

## UEP-07：改造本地 embedding 实现为 backend

### 背景

本地 embedding 当前通过 gRPC 服务实现业务端口。

### 工作内容

- 将 `LocalEmbeddingService` 改为 `EmbeddingBackend`，或拆出 `LocalEmbeddingBackend`。
- `backendName()` 返回 `local`。
- 保留现有 gRPC deadline 与调用逻辑。
- 移除对业务端口的直接实现。
- 移除 embedding provider 条件装配。

### 涉及文件

- `src/main/java/com/smart/vision/core/integration/multimodal/service/local/LocalEmbeddingService.java`
- 或新增 `LocalEmbeddingBackend.java`

### 验收标准

- `app.embedding.backend=local` 时实际调用本地 gRPC backend。
- 本地 backend 不再作为业务端口 Bean 暴露。

## UEP-08：移除 embedding 对 `app.capability-provider.embedding` 的依赖

### 背景

本方案要求 embedding 不再跟随 capability-provider 切换。

### 工作内容

- 从配置中移除或废弃：

```yaml
app:
  capability-provider:
    embedding: ...
```

- 搜索代码中所有 `capability-provider.embedding` 引用。
- 保留其他能力：
  - `rerank`
  - `gen`
  - `ocr`
  - `object-storage`

### 涉及文件

- `src/main/resources/application.yaml`
- `src/main/resources/application-local.yaml`
- `src/main/resources/application-cloud-aliyun.yaml`
- `src/main/resources/application-cloud-volcengine.yaml`
- 各 embedding 实现类。

### 验收标准

- 修改 local/cloud profile 不会改变 embedding backend。
- 只有 `app.embedding.backend` 会改变 embedding backend。

## UEP-09：调整 vector profile 使用 `app.embedding.backend`

### 背景

当前 `VectorConfig` 与 `KbSegmentConfig` 的 profile 使用 `app.capability-provider.embedding`。该字段废弃后，需要改为全局 backend。

### 工作内容

- `VectorConfig`：
  - `embeddingProvider` 改为读取 `app.embedding.backend`。
  - `getVectorProfile()` 保持 `backend + model + dimension + preprocessVersion`。
- `KbSegmentConfig`：
  - 同上。
  - dimension 使用 `getResolvedDimension()`。

### 涉及文件

- `src/main/java/com/smart/vision/core/common/config/VectorConfig.java`
- `src/main/java/com/smart/vision/core/common/config/KbSegmentConfig.java`

### 验收标准

- `VectorConfig#getVectorProfile()` 使用 `app.embedding.backend`。
- `KbSegmentConfig#getVectorProfile()` 使用 `app.embedding.backend`。
- local/cloud profile 不影响 profile，除非显式改 `app.embedding.backend`。

## UEP-10：统一 local/cloud 配置与索引版本

### 背景

统一 backend/model/dimension 后，需要创建新物理索引，避免新旧向量混写。

### 工作内容

- 在 `application.yaml` 或环境变量中配置全局 embedding：

```yaml
app:
  embedding:
    backend: aliyun
    model: multimodal-embedding-v1
    dimension: 1024
    preprocess-version: v1
    image-input-mode: url
```

- local/cloud profile 不再覆盖 embedding backend。
- `app.vector.dimension` 与 `app.kb-segment.dimension` 对齐 `app.embedding.dimension`。
- 升级：
  - `app.vector.indexVersion`
  - `app.kb-segment.indexVersion`

### 涉及文件

- `src/main/resources/application.yaml`
- `src/main/resources/application-local.yaml`
- `src/main/resources/application-cloud-aliyun.yaml`
- `src/main/resources/application-cloud-volcengine.yaml`

### 验收标准

- local/cloud 生成相同 vector profile。
- `smart_gallery_*` 与 `kb_segment_*` 仍是不同物理索引。
- 两套索引维度一致。

## UEP-11：增加启动校验与单元测试

### 背景

本次改造要防止出现多个业务侧 embedding provider、backend 配置错误、维度不一致。

### 工作内容

- 测试 `UnifiedEmbeddingProvider` backend 选择。
- 测试不存在 backend 时 fail fast。
- 测试重复 backendName 时 fail fast。
- 测试只存在一个 `SearchEmbeddingPort` Bean。
- 测试只存在一个 `IngestionEmbeddingPort` Bean。
- 测试 profile 生成使用 `app.embedding.backend`。
- 增加维度一致性校验：
  - `app.embedding.dimension`
  - `app.vector.dimension`
  - `app.kb-segment.dimension`

### 建议测试

```text
UnifiedEmbeddingProviderTest
EmbeddingBackendRegistryTest
VectorConfigTest
KbSegmentConfigTest
EmbeddingConfigurationHealthCheckTest
```

### 验收标准

- 关键配置错误在启动或单测阶段暴露。
- provider Bean 不冲突。
- profile 生成逻辑稳定。

## UEP-12：扩展 ES CLI 默认 allowlist 覆盖双索引

### 背景

双索引链路保留后，ES CLI 默认应允许检查两套索引。

### 工作内容

- `EsCliAccessControl` 注入 `KbSegmentConfig`。
- 默认 allowlist 增加：
  - `kbSegmentConfig.getReadAlias()`
  - `kbSegmentConfig.getWriteAlias()`
  - `kbSegmentConfig.getPhysicalIndexName()`
- 显式配置 `allowed-index-patterns` 时仍以显式配置为准。

### 涉及文件

- `src/main/java/com/smart/vision/core/search/escli/domain/EsCliAccessControl.java`
- `src/test/java/com/smart/vision/core/search/escli/domain/EsCliAccessControlTest.java`

### 验收标准

- 默认允许访问 `smart_gallery_*`。
- 默认允许访问 `kb_segment_*`。
- 显式 allowlist 行为不变。

## UEP-13：小批量双链路验证与阈值校准

### 背景

统一 backend 后，需要确认两条链路都能写入和检索，并评估分数分布变化。

### 工作内容

- 准备固定样本：
  - 图片样本。
  - OCR 图片样本。
  - 文本文档样本。
- 入库图片，确认双写：
  - `smart_gallery_*`
  - `kb_segment_*`
- 入库文本，确认写入 `kb_segment_*`。
- 回归接口：
  - `/api/v1/vision/search-page`
  - `/api/v1/vision/search-by-image`
  - `/api/v1/vision/similar`
  - `/api/v1/vision/vector-compare`
  - `/api/v1/search/kb`
- 评估阈值：
  - `app.search.vector-min-score`
  - `app.search.quality-absolute-min-score`

### 验收标准

- 两条索引链路均可写入。
- 两条搜索链路均可检索。
- 主要 demo query 召回稳定。
- 阈值调整建议明确。

## UEP-14：全量回填与 alias 切换 runbook

### 背景

backend/model/dimension/preprocessVersion 变化后，旧向量不能复用。

### 工作内容

- 制定回填范围：
  - legacy 图片索引。
  - `kb_segment`。
- 制定执行步骤：
  - 批大小。
  - 失败重试。
  - 进度记录。
  - 校验方式。
- 制定 alias 切换步骤。
- 制定回滚步骤。
- 明确旧索引保留周期。

### 验收标准

- 有可执行 runbook。
- 回填后新索引文档数符合预期。
- 抽样查询通过。
- alias 可回滚。

## 测试命令建议

根据实际改动选择运行：

```bash
mvn -q -Dtest=UnifiedEmbeddingProviderTest,EmbeddingBackendRegistryTest,VectorConfigTest,KbSegmentConfigTest test
```

如果改动 ES CLI：

```bash
mvn -q -Dtest=EsCliAccessControlTest test
```

如果改动配置装配或入库检索链路：

```bash
mvn -q -Dtest=ImageIngestionServiceImplRetryFlowTest,ImageSegmentIndexWriterTest,UnifiedSearchServiceImplTest,VectorCompareServiceImplTest test
```

## 最终验收 Checklist

- [ ] `app.embedding.backend` 是唯一 embedding backend 选择入口。
- [ ] `app.capability-provider.embedding` 已移除或不再生效。
- [ ] Spring 容器只有一个 `SearchEmbeddingPort` Bean。
- [ ] Spring 容器只有一个 `IngestionEmbeddingPort` Bean。
- [ ] 阿里云、火山、本地实现均为 `EmbeddingBackend`。
- [ ] `VectorConfig` profile 使用 `app.embedding.backend`。
- [ ] `KbSegmentConfig` profile 使用 `app.embedding.backend`。
- [ ] local/cloud profile 不会隐式改变 embedding backend。
- [ ] `app.vector.dimension`、`app.kb-segment.dimension`、`app.embedding.dimension` 一致。
- [ ] 新索引版本已规划。
- [ ] 双索引链路小批量验证通过。
- [ ] 阈值校准完成或有明确调整建议。
- [ ] 全量回填 runbook 已准备。
