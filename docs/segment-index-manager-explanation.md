# SegmentIndexManagerImpl 详细解析

## 一、整体架构

`SegmentIndexManagerImpl` 是 **Elasticsearch 段索引(Segment Index)的生命周期管理器**，负责索引的创建、重建、状态查询。

### 核心设计思路

| 设计点 | 说明 |
|--------|------|
| **状态机模型** | 使用 `AtomicReference<SegmentIndexState>` + CAS 实现无锁状态转换 |
| **异步执行** | 创建和重建都在 `indexInitExecutor`(单线程池) 上异步运行 |
| **读写屏障** | 重建期间通过 `SegmentIndexWriteBarrier`(读写锁) 阻止写入 |
| **Alias 双指针** | 使用 `read-alias` + `write-alias` 指向同一物理索引，支持原子切换 |

### 状态枚举

```
SegmentIndexStatus:
  NOT_READY → INITIALIZING → READY → REBUILDING → READY
                   ↓                        ↓
                NOT_READY               READY (failed)
```

### 接口定义

```java
public interface SegmentIndexManager {
    void asyncCreate();                                    // 1a: 异步创建索引
    void createPendingRebuild(String reason, int dim);     // 1b: 创建待确认重建任务
    SegmentIndexStatusDTO status();                        // 1c: 查询状态
    boolean quickCheck();                                  // 1c: 快速可读检查
    boolean retryCreate();                                 // 1d: 重试创建
    boolean confirmRebuild(String taskId);                 // 1e: 确认并执行重建
    String prepareRebuild();                               // 1e: 准备重建(比较+创建任务)
}
```

---

## 二、内部数据结构

### SegmentIndexState (不可变 record)

核心状态容器，所有字段都是 `final`，通过 `withXxx()` 方法创建新实例：

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | `SegmentIndexStatus` | 当前状态机状态 |
| `lastError` | `String` | 最近的错误信息 |
| `pendingRebuild` | `PendingRebuildState` | 待确认的重建任务 |
| `rebuildProgress` | `RebuildProgressState` | 重建进度 |
| `indexExists` | `Boolean` | 物理索引是否存在 (null=未查询) |
| `readable` | `boolean` | 当前是否可读 |
| `writable` | `boolean` | 当前是否可写 (仅 READY 状态可为 true) |
| `actualDim` | `Integer` | ES 中实际 embedding 维度 |
| `actualModel` | `String` | ES 中实际模型名 |
| `actualProfileFingerprint` | `String` | ES 中实际 profile 指纹 |

#### withXxx 方法说明

| 方法 | 触发场景 | 状态转换 |
|------|----------|----------|
| `withStatus(newStatus, error)` | 通用状态+错误更新 | 保留其他字段不变 |
| `withPendingRebuild(pending)` | 创建待确认重建任务 | READY → READY (pendingRebuild 非空) |
| `withRebuildProgress(progress)` | 重建进度更新 | REBUILDING 内进度变化 |
| `withIndexInfo(...)` | ES 查询后更新索引信息 | 缓存索引元数据 |
| `claimRebuild()` | 确认执行重建 | READY → REBUILDING, writable=false |
| `createSucceeded(profile)` | 创建完成 | INITIALIZING → READY |
| `rebuildSucceeded(profile)` | 重建完成 | REBUILDING → READY |
| `rebuildFailed(error, ...)` | 重建失败 | REBUILDING → READY + error |

### PendingRebuildState

```
taskId (UUID) | targetProfile (EmbeddingProfile) | reason (String) | createdAt (ISO时间戳)
```

### RebuildProgressState

```
migrated (已迁移数) | total (总数) | phase (MIGRATING | SWITCHING_ALIAS | COMPLETED | FAILED)
```

---

## 三、方法详解与流程图

### 3.1 启动相关

#### `onReady()` — 应用启动监听器

```
@EventListener(ApplicationReadyEvent.class)
```

```
应用启动完成
     │
     ▼
┌─────────────┐
│  status()   │ 查询当前索引状态
└──────┬──────┘
       │
       ▼
  indexExists?
      / \
    否   是
     │    │
     ▼    ▼
┌──────────────┐   ┌────────────────────┐
│ 获取活跃的    │   │ markReadyFromStatus │
│ Embedding    │   │ NOT_READY → READY   │
│ Profile      │   └────────────────────┘
└──────┬───────┘
       │
       ▼
  profile 存在?
      / \
    是   否
     │    │
     ▼    ▼
┌──────────────┐  ┌─────────────────────┐
│tryScheduleCreate│ │ log: 无活跃embedding│
│ 异步创建索引   │  │ 配置，跳过           │
└──────────────┘  └─────────────────────┘
```

**作用:** 应用启动后自动检查 ES 索引是否存在。如果不存在且有活跃的 embedding 配置，触发异步创建；如果已存在，标记为 READY。

#### `markReadyFromStatus(SegmentIndexStatusDTO)` — 标记就绪

仅在当前状态为 `NOT_READY` 时，将状态转换为 `READY`，同时将 ES 中读取到的实际索引信息写入 `stateRef`。如果当前状态已不是 `NOT_READY`，则不做任何操作。

---

### 3.2 索引创建 (async create)

#### `asyncCreate()` — 对外公开入口

直接委托给 `tryScheduleCreate()`。

#### `tryScheduleCreate()` / `tryScheduleCreate(EmbeddingProfile)` — CAS 调度创建

```
┌─────────────────────────┐
│ 获取活跃 EmbeddingProfile │
└────────────┬────────────┘
             │
             ▼
      profile == null? ──是──→ return false
             │
            否
             ▼
     ┌─────────── CAS 循环 ───────────┐
     │                                 │
     │  stateRef.get()                 │
     │      │                          │
     │      ▼                          │
     │  status != NOT_READY? ──是──→ return false
     │      │                          │
     │     否                          │
     │      ▼                          │
     │  CAS: NOT_READY → INITIALIZING  │
     │      │                          │
     │    成功? ──否──→ continue ──────┘
     │      │
     │     是
     │      ▼
     │  indexInitExecutor.execute(
     │      () -> executeCreate(profile))
     │      │
     │      ▼
     │  提交成功? ──否──→ rollbackCreateClaim → return false
     │      │
     │     是
     │      ▼
     │  return true
```

**作用:** 通过 CAS 原子操作将状态从 `NOT_READY` 翻转为 `INITIALIZING`，避免并发重复创建。翻转成功后在线程池中异步执行实际创建。

#### `executeCreate(EmbeddingProfile)` — 实际执行创建

```
┌────────────────────┐
│ indexOpLock.lock() │ 互斥锁，防止与重建并发
└────────┬───────────┘
         │
         ▼
┌────────────────┐
│ doCreate(profile)│
└────────┬───────┘
         │
    成功? │
     / \  │
   成功  失败
     │     │
     ▼     ▼
┌──────────────┐ ┌──────────────────┐
│ createSucceeded│ │ NOT_READY + error│
│ → READY       │ │ (回滚到初始状态)  │
└──────────────┘ └──────────────────┘
         │
         ▼
┌──────────────────────┐
│ indexOpLock.unlock() │
└──────────────────────┘
```

#### `doCreate(EmbeddingProfile)` — 创建物理索引并绑定别名

```
┌──────────────────────────────┐
│ newPhysicalIndexName()       │
│ → "segments_1712345678901"   │ 时间戳后缀保证唯一
└────────────┬─────────────────┘
             │
             ▼
┌─────────────────────────────────────┐
│ createPhysicalIndex(name, profile)  │
│  ├─ 加载 es-settings.json           │
│  ├─ 加载 mapping 模板               │
│  │  替换 @DIMS@ 为实际维度          │
│  ├─ 写入 _meta (profile 信息)       │
│  └─ esClient.indices().create()     │
└────────────┬────────────────────────┘
             │
             ▼
┌──────────────────────────────┐
│ aliasManager.bindAliases(    │
│   physicalIndexName)         │
│  ├─ 校验当前无 alias          │
│  ├─ 同时绑定 read-alias       │
│  │   + write-alias(isWrite)  │
│  └─ 验证绑定一致性            │
└────────────┬─────────────────┘
             │
        绑定失败?
             │
            是
             ▼
┌──────────────────────────────┐
│ cleanupFailedTargetIndex()   │ 清理孤儿索引
│  ├─ 检查是否被 alias 引用     │
│  └─ 安全删除物理索引          │
└──────────────────────────────┘
```

---

### 3.3 状态查询

#### `status()` — 完整索引状态查询

```
┌──────────────────────┐
│ 获取活跃 EmbeddingProfile│ (expected 值)
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ stateRef.get()       │
└──────────┬───────────┘
           │
    indexExists != null? (已缓存)
           / \
          是  否
           │   │
           │   ▼
           │  ┌─────────────────────────┐
           │  │ aliasManager.inspect()  │ 查询 alias 拓扑
           │  └────────────┬────────────┘
           │              │
           │              ▼
           │  ┌──────────────────────────────┐
           │  │ esClient.indices().getMapping │ 读取 mapping _meta:
           │  │  - embeddingProfileVersion    │
           │  │  - embeddingProfileFingerprint│
           │  │  - embeddingCapability        │
           │  │  - embeddingModel             │
           │  │  - embeddingDimension         │
           │  └────────────┬───────────────────┘
           │              │
           │              ▼
           │  ┌──────────────────────────────┐
           │  │ 校验 metadata 有效性           │
           │  │ version==1 && dim匹配?        │
           │  │ 不匹配 → fingerprint=null     │
           │  └────────────┬──────────────────┘
           │              │
           │              ▼
           │  ┌──────────────────────────────┐
           │  │ stateRef.updateAndGet(        │
           │  │   withIndexInfo(...))         │ 更新缓存
           │  └────────────┬──────────────────┘
           │              │
           ▼              ▼
  ┌─────────────────────────────────────────┐
  │         构建 SegmentIndexStatusDTO        │
  │  - status, indexExists, readable/writable│
  │  - actualDim/Model/Fingerprint (ES中的)  │
  │  - expectedDim/Model/Fingerprint (配置的) │
  │  - pendingRebuild, rebuildProgress       │
  │  - lastError                             │
  └─────────────────────────────────────────┘
```

**作用:** 这是最复杂的方法。首次调用时主动查询 ES alias 拓扑和 mapping 元数据，将实际 embedding 信息缓存到 `stateRef` 中。后续调用直接使用缓存。返回的 `SegmentIndexStatusDTO` 是前端展示索引健康状态的核心数据，同时包含 actual(实际) 和 expected(期望) 的对比信息，供调用者判断是否需要重建。

#### `quickCheck()` — 快速可读性检查

直接调用 `aliasManager.inspect().readable()`，不查 mapping，不更新缓存，极轻量。

---

### 3.4 重试创建

#### `retryCreate()` — 重试失败的创建

```
status == NOT_READY? ──否──→ log warning + return false
     │
     是
     ▼
  tryScheduleCreate()
```

**作用:** 当创建失败后状态回退到 `NOT_READY`，允许用户通过 API 手动重试。

---

### 3.5 索引重建 (核心流程)

#### `prepareRebuild()` — 准备重建任务

```
┌──────────────────────┐
│ status(expectedProfile)│ 查询 actual vs expected
└──────────┬───────────┘
           │
           ▼
   indexExists && actualDim != null?
           / \
          否  是
           │   │
           │   ▼
           │  actualDim == expectedDim?
           │      / \
           │     是  否
           │      │   │
           │      ▼   │
           │  fingerprint│
           │  相同?      │
           │   / \      │
           │  是  否     │
           │   │   │     │
           │   ▼   ▼     ▼
           │ 不需要  model变化  dim变化
           │  重建  需要重建   需要重建
           │         │        │
           └────┬────┴────────┘
                │
                ▼
       createPendingRebuildTask()
       ├─ CAS: READY → READY (with pendingRebuild)
       └─ return taskId
```

**作用:** 比较 ES 中实际索引的 embedding 配置与当前活跃配置。如果维度或模型指纹不同，创建一个待确认的重建任务。

#### `createPendingRebuildTask(String, EmbeddingProfile)` — CAS 创建重建任务

```
┌────────────── CAS 循环 ──────────────┐
│ stateRef.get()                       │
│    │                                 │
│    ▼                                 │
│ status != READY? ──是──→ throw       │
│    │                                 │
│   否                                 │
│    ▼                                 │
│ 已有相同 fingerprint 的 pending?      │
│    │                                 │
│   是──→ return 已有 taskId (幂等)     │
│    │                                 │
│   否                                 │
│    ▼                                 │
│ CAS: current → withPendingRebuild()  │
│    │                                 │
│  成功──→ return new taskId            │
└──────────────────────────────────────┘
```

#### `confirmRebuild(String taskId)` — 确认并执行重建

```
┌──────────────────┐
│ tryClaimRebuild  │ CAS: READY → REBUILDING
└────────┬─────────┘
         │
    claim == null?
        / \
       是  否
        │   │
        ▼   ▼
  return false  indexInitExecutor.execute(
                   () -> executeRebuild(claim))
```

#### `executeRebuild(RebuildClaim)` — 执行重建(加锁)

```
┌────────────────────────────┐
│ indexOpLock.lock()         │ 互斥锁: 防创建并发
└────────────┬───────────────┘
             │
             ▼
┌────────────────────────────┐
│ indexWriteBarrier          │
│ .withExclusiveRebuildPermit│ 写锁: 阻止所有写入
│   └─ executeRebuildExclusively│
└────────────┬───────────────┘
             │
             ▼
┌────────────────────────────┐
│ indexOpLock.unlock()       │
└────────────────────────────┘
```

#### `executeRebuildExclusively(RebuildClaim)` — 重建核心

```
┌────────────────────────────┐
│ embeddingPort.openSession  │ 打开 embedding 会话
└────────────┬───────────────┘
             │
             ▼
┌────────────────────────────┐
│ doRebuild(profile, session)│
└────────────┬───────────────┘
             │
        成功? │
         / \  │
       成功  失败
         │     │
         ▼     ▼
┌──────────────┐ ┌────────────────────────┐
│rebuildSucceeded│ │ rebuildFailed           │
│ → READY       │ │ → READY + error         │
│               │ │ + 保留 alias 可读写状态   │
└──────────────┘ └────────────────────────┘
```

#### `doRebuild(EmbeddingProfile, EmbeddingSession)` — 实际重建流程

```
┌────────────────────────────────────────────┐
│ 1. aliasManager.requireValid()             │
│    校验 read/write alias 唯一且指向同一索引  │
│    → oldPhysicalIndex                      │
└────────────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────┐
│ 2. esClient.indices().refresh(oldIndex)    │ 确保写入可见
│    esClient.count(oldIndex)                │ 获取文档总数
│    → totalDocs                             │
└────────────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────┐
│ 3. newPhysicalIndexName()                  │
│    createPhysicalIndex(newIndex, profile)  │ 建新索引
│    (settings + mapping + _meta)            │
└────────────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────┐
│ 4. migrateData(oldIdx, newIdx, totalDocs)  │ 存量迁移
│    ┌──────────────────────────────────┐    │
│    │ Scroll 遍历旧索引所有文档          │    │
│    │ 每批 SCROLL_BATCH_SIZE=500 条     │    │
│    │   ├─ prepareMigrationBatch()     │    │
│    │   │   ├─ 获取文档 segmentId       │    │
│    │   │   ├─ computeNewEmbedding()   │    │
│    │   │   │   ├─ IMAGE → thumbnail   │    │
│    │   │   │   │   → storagePort      │    │
│    │   │   │   │   → embeddingSession │    │
│    │   │   │   └─ TEXT  → contentText │    │
│    │   │   │       → embeddingSession │    │
│    │   │   └─ validateEmbedding()     │    │
│    │   │       ├─ 维度校验             │    │
│    │   │       └─ 非有限值校验          │    │
│    │   └─ writeMigrationBatch()       │    │
│    │       └─ Bulk API 写入新索引      │    │
│    │       └─ 校验响应无 error          │    │
│    └──────────────────────────────────┘    │
│    ┌──────────────────────────────────┐    │
│    │ refresh(newIndex) + count 校验    │    │
│    │ validateMigrationCounts()        │    │
│    │ source == migrated == target     │    │
│    └──────────────────────────────────┘    │
└────────────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────┐
│ 5. aliasManager.switchAliases(             │
│      oldIndex, newIndex)                   │
│    ┌──────────────────────────────────┐    │
│    │ remove read-alias  from oldIndex  │    │
│    │ add    read-alias  to   newIndex  │    │
│    │ remove write-alias from oldIndex  │    │
│    │ add    write-alias to   newIndex  │    │
│    │ (所有操作在一个原子请求中)         │    │
│    └──────────────────────────────────┘    │
│    验证: newIndex == physicalIndex()       │
└────────────────────┬───────────────────────┘
                     │
                     ▼
                 ┌──────┐
                 │ 完成  │ 旧索引保留作为回滚快照
                 └──────┘
```

**关键设计决策:**
- 旧索引 **保留不删除**，作为回滚快照
- 迁移失败时新索引会被 `cleanupFailedTargetIndex()` 清理
- alias 切换是 **原子操作**（一个 `updateAliases` 请求包含全部 4 个 action）
- `computeNewEmbedding` 对 IMAGE 类型使用 thumbnail + storagePort 构建 AI 输入 URL

---

### 3.6 辅助方法

| 方法 | 可见性 | 作用 |
|------|--------|------|
| `loadAndProcessMapping(int dims)` | private | 从 classpath 加载 mapping JSON 模板，将 `@DIMS@` 占位符替换为实际维度值 |
| `newPhysicalIndexName()` | private | 生成 `{indexName}_{timestamp}` 格式的唯一物理索引名 |
| `createPhysicalIndex(name, profile)` | private | 调用 ES API 创建索引，写入 settings + mapping + `_meta` 元数据 |
| `toMappingMetadata(profile)` | static | 将 `EmbeddingProfile` 转为 ES mapping `_meta` 的 Map 结构 |
| `validateEmbedding(id, embedding, dim)` | static | 校验向量维度匹配且无非有限值(NaN/Inf) |
| `validateMigrationCounts(src, migrated, target)` | static | 校验迁移前后文档数一致 |
| `clearScrollQuietly(scrollId)` | private | 安全清理 ES scroll 上下文，失败仅 warn |
| `cleanupFailedTargetIndex(name, ...)` | private | 清理创建/重建失败的孤儿索引，删除前先验证未被 alias 引用 |
| `isReferencedByAlias(name, alias)` | private | 检查物理索引是否被某个 alias 引用 |
| `formatBulkFailure(BulkResponseItem)` | private | 格式化 bulk 写入错误信息 |
| `readMetadataString(map, key)` | static | 从 ES `_meta` 读取字符串值 |
| `readMetadataInteger(map, key)` | static | 从 ES `_meta` 读取整数值 |

---

## 四、完整生命周期流程图

```
                          ┌─────────────────┐
                          │  应用启动         │
                          │  onReady()       │
                          └────────┬────────┘
                                   │
                            status() 查 ES
                                   │
                          indexExists?
                              /        \
                            否           是
                             │            │
                             ▼            ▼
                   ┌─────────────┐  ┌──────────┐
                   │NOT_READY    │  │  READY   │
                   │(初始状态)   │  │markReady │
                   └──────┬──────┘  └──────────┘
                          │
                  tryScheduleCreate()
                    CAS 翻转状态
                          │
                          ▼
                   ┌─────────────┐
                   │INITIALIZING │
                   │异步执行创建  │
                   └──────┬──────┘
                          │
                  executeCreate()
                    indexOpLock
                    doCreate()
                          │
                    成功? │
                    / \   │
                  成功  失败
                    │     │
                    ▼     ▼
              ┌──────────┐ ┌──────────┐
              │  READY   │ │NOT_READY │ (可 retryCreate)
              └────┬─────┘ └──────────┘
                   │
          ┌────────┼────────┐
          │        │        │
    正常读写   prepareRebuild  status()
          │        │
          │   actual ≠ expected?
          │        │
          │       是
          │        │
          │   createPendingRebuildTask
          │   状态: READY + pendingRebuild
          │        │
          │   confirmRebuild(taskId)
          │    CAS: READY → REBUILDING
          │        │
          │        ▼
          │   ┌──────────┐
          │   │REBUILDING│
          │   │executeRebuild│
          │   └────┬─────┘
          │        │
          │   indexWriteBarrier
          │   (写锁,阻止写入)
          │        │
          │   doRebuild()
          │   ├─ Scroll 旧索引
          │   ├─ 重新 embedding
          │   ├─ Bulk 写新索引
          │   └─ switchAliases
          │        │
          │   成功? │
          │   / \   │
          │ 成功  失败
          │   │     │
          │   ▼     ▼
          │ ┌──────┐ ┌──────────────┐
          │ │READY │ │READY + error │
          │ └──────┘ │(保留旧alias) │
          │          └──────────────┘
          │
          ▼
    ┌─────────────────┐
    │   正常服务        │
    │   readable=true  │
    │   writable=true  │
    └─────────────────┘
```

---

## 五、关键设计决策

1. **CAS 无锁状态机**: 所有状态转换通过 `stateRef.compareAndSet()` 实现，避免 `synchronized` 的性能损耗，同时保证并发安全。

2. **双锁机制**: `indexOpLock`(ReentrantLock) 防止创建和重建并发执行；`indexWriteBarrier`(ReadWriteLock) 在重建期间阻止新的写入请求——写入方获取读锁，重建获取写锁。

3. **两阶段重建**: `prepareRebuild` → `confirmRebuild`，给运维人员确认窗口，防止误操作触发昂贵的全量重建。

4. **旧索引保留**: 重建完成后旧物理索引不删除，作为隐式回滚快照。回滚需要手动操作。

5. **幂等性**: `createPendingRebuildTask` 对相同 fingerprint 的重复请求返回已有 taskId，不创建重复任务。

6. **元数据校验**: `status()` 方法会校验 mapping `_meta` 中的 `embeddingProfileVersion` 和 `embeddingDimension` 是否一致，不一致时清空 fingerprint 以触发重建。

---

## 六、依赖关系

| 依赖 | 类型 | 用途 |
|------|------|------|
| `ElasticsearchClient` | ES 客户端 | 索引创建/删除/查询/scroll/bulk |
| `SegmentIndexConfig` | 配置 | indexName, readAlias, writeAlias |
| `EmbeddingProfileProvider` | 领域端口 | 获取活跃的 embedding 配置 |
| `SearchEmbeddingPort` | 领域端口 | 打开 embedding 会话，计算向量 |
| `SearchObjectStoragePort` | 领域端口 | 构建图片 AI 输入 URL |
| `SegmentIndexWriteBarrier` | 应用服务 | 重建期间阻止写入 |
| `SegmentIndexAliasManager` | 基础设施 | alias 拓扑查询/绑定/切换 |
| `indexInitExecutor` | 线程池 | 异步执行创建/重建(单线程, CallerRunsPolicy) |

### 调用方

| 调用方 | 调用的方法 |
|--------|-----------|
| `IndexController` | `status()`, `retryCreate()`, `confirmRebuild()`, `prepareRebuild()` |
| `CapabilityConfigServiceImpl` | `asyncCreate()`, `status()` |
| `SegmentBulkWriter` | `status()` |
| `EsSegmentRepository` | `status()` |
