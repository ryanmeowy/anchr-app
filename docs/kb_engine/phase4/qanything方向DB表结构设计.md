# QAnything 方向 DB 表结构设计

## 1. 设计边界

DB 表只承载产品主数据和可审计状态，不承载大段全文检索内容。

- DB：知识库、文档资产、入库任务、会话、引用快照、设置、活动记录。
- ES：segment/chunk、BM25 字段、向量字段、bbox、snippet、anchor。
- OSS：原始文件、预览文件、缩略图。
- Redis：短期 token、任务锁、previewUrl 缓存、任务执行中的瞬时进度。

`document_asset` 是用户可管理的一份资料，`segment` 是检索和引用的证据片段。二者必须分层，避免文档管理、删除、重试、去重都从 ES chunk 反推。

## 2. 命名与通用字段

表名使用 snake_case，主键使用字符串 ID，便于后续兼容分布式 ID、外部导入和前端引用。

通用字段建议：

```sql
id            varchar(64) primary key
workspace_id  varchar(64) not null default 'default'
created_by    varchar(64) not null default 'system'
updated_by    varchar(64) not null default 'system'
created_at    timestamp not null
updated_at    timestamp not null
deleted_at    timestamp null
```

说明：

- P0 可以使用固定 `workspace_id=default` 和 `created_by=system`。
- 需要软删除的业务表保留 `deleted_at`，默认查询过滤已删除数据。
- 状态字段使用字符串枚举，便于接口返回和排查。
- JSON 字段用于快照和低频扩展，不用于高频筛选条件。

## 3. P0 核心表

### 3.1 knowledge_base

知识库顶层资源。

```sql
create table knowledge_base (
  id                varchar(64) primary key,
  workspace_id      varchar(64) not null default 'default',
  name              varchar(128) not null,
  description       text,
  status            varchar(32) not null,
  document_count    int not null default 0,
  segment_count     int not null default 0,
  last_ingested_at  timestamp null,
  created_by        varchar(64) not null default 'system',
  updated_by        varchar(64) not null default 'system',
  created_at        timestamp not null,
  updated_at        timestamp not null,
  deleted_at        timestamp null
);
```

状态：

```text
ACTIVE
ARCHIVED
DELETING
```

建议索引：

```sql
create index idx_kb_workspace_status on knowledge_base(workspace_id, status);
create index idx_kb_updated_at on knowledge_base(updated_at);
```

### 3.2 document_asset

文档资产表，表示用户上传或导入的一份资料。

```sql
create table document_asset (
  id                  varchar(64) primary key,
  workspace_id        varchar(64) not null default 'default',
  kb_id               varchar(64) not null,
  file_name           varchar(512) not null,
  title               varchar(512),
  file_type           varchar(32) not null,
  mime_type           varchar(128),
  size_bytes          bigint,
  file_hash           varchar(128),
  object_key          varchar(1024),
  preview_object_key  varchar(1024),
  thumbnail_key       varchar(1024),
  source_url          text,
  parse_status        varchar(32) not null,
  index_status        varchar(32) not null,
  segment_count       int not null default 0,
  embedding_profile   varchar(128),
  error_code          varchar(128),
  error_message       text,
  created_by          varchar(64) not null default 'system',
  updated_by          varchar(64) not null default 'system',
  created_at          timestamp not null,
  updated_at          timestamp not null,
  deleted_at          timestamp null
);
```

`file_hash` 由后端基于文件内容计算，建议使用 SHA-256。OSS 返回的 `etag`、`objectKey`、`url` 不应直接等同为业务 `file_hash`。

状态：

```text
parse_status: PENDING / RUNNING / SUCCESS / FAILED
index_status: PENDING / RUNNING / SUCCESS / FAILED
```

建议索引：

```sql
create index idx_doc_kb_status on document_asset(kb_id, parse_status, index_status);
create index idx_doc_hash on document_asset(kb_id, file_hash);
create index idx_doc_created_at on document_asset(kb_id, created_at);
```

### 3.3 ingestion_task

入库任务主表，表示一批导入、重试、重解析或重向量化任务。

```sql
create table ingestion_task (
  id              varchar(64) primary key,
  workspace_id    varchar(64) not null default 'default',
  kb_id           varchar(64) not null,
  source_type     varchar(32) not null,
  status          varchar(32) not null,
  total_count     int not null default 0,
  success_count   int not null default 0,
  failure_count   int not null default 0,
  running_count   int not null default 0,
  created_by      varchar(64) not null default 'system',
  updated_by      varchar(64) not null default 'system',
  created_at      timestamp not null,
  updated_at      timestamp not null,
  finished_at     timestamp null
);
```

状态：

```text
source_type: UPLOAD / URL / RETRY / REPARSE / REEMBED
status: PENDING / RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED
```

建议索引：

```sql
create index idx_task_kb_created on ingestion_task(kb_id, created_at);
create index idx_task_status on ingestion_task(status);
```

### 3.4 ingestion_task_item

入库任务项，表示单个文件或 URL 的处理状态。

```sql
create table ingestion_task_item (
  id              varchar(64) primary key,
  task_id         varchar(64) not null,
  kb_id           varchar(64) not null,
  asset_id        varchar(64),
  file_name       varchar(512),
  file_hash       varchar(128),
  source_url      text,
  stage           varchar(32) not null,
  status          varchar(32) not null,
  progress        int not null default 0,
  dedupe_result   varchar(32),
  error_code      varchar(128),
  error_message   text,
  created_at      timestamp not null,
  updated_at      timestamp not null,
  finished_at     timestamp null
);
```

状态：

```text
stage: UPLOAD / PARSE / CHUNK / EMBED / INDEX / ASKABLE
status: PENDING / RUNNING / SUCCESS / FAILED / SKIPPED
dedupe_result: NEW / SKIPPED / OVERWRITTEN / VERSIONED
```

建议索引：

```sql
create index idx_task_item_task on ingestion_task_item(task_id);
create index idx_task_item_asset on ingestion_task_item(asset_id);
create index idx_task_item_kb_status on ingestion_task_item(kb_id, status);
```

## 4. P1 体验增强表

### 4.1 conversation_session

对话会话表。

```sql
create table conversation_session (
  id             varchar(64) primary key,
  workspace_id   varchar(64) not null default 'default',
  title          varchar(256),
  kb_scope       json,
  status         varchar(32) not null,
  created_by     varchar(64) not null default 'system',
  updated_by     varchar(64) not null default 'system',
  created_at     timestamp not null,
  updated_at     timestamp not null,
  deleted_at     timestamp null
);
```

建议索引：

```sql
create index idx_session_workspace_user_updated on conversation_session(workspace_id, created_by, updated_at);
```

### 4.2 conversation_turn

对话轮次表。`result_cards`、`retrieval_trace` 等字段保存回答时的快照，避免历史消息刷新后重新检索导致引用变化。

```sql
create table conversation_turn (
  id                   varchar(64) primary key,
  session_id           varchar(64) not null,
  question             text not null,
  rewritten_query      text,
  answer               text,
  answer_mode          varchar(32),
  kb_scope             json,
  retrieval_trace      json,
  result_cards         json,
  suggested_questions  json,
  created_at           timestamp not null
);
```

建议索引：

```sql
create index idx_turn_session_created on conversation_turn(session_id, created_at);
```

### 4.3 conversation_citation

回答引用表，用于支撑引用卡片、最近引用和“为什么引用这段”。

```sql
create table conversation_citation (
  id               varchar(64) primary key,
  turn_id          varchar(64) not null,
  kb_id            varchar(64),
  asset_id         varchar(64),
  segment_id       varchar(128) not null,
  citation_index   int not null,
  answer_claim     text,
  citation_reason  text,
  snapshot          json,
  created_at        timestamp not null
);
```

建议索引：

```sql
create index idx_citation_turn on conversation_citation(turn_id);
create index idx_citation_segment on conversation_citation(segment_id);
create index idx_citation_asset on conversation_citation(asset_id);
```

### 4.4 activity_event

轻量活动事件表，用于 Ask First 首页的最近问题、最近引用、最近导入等聚合。

```sql
create table activity_event (
  id             varchar(64) primary key,
  workspace_id   varchar(64) not null default 'default',
  user_id        varchar(64) not null default 'system',
  event_type     varchar(64) not null,
  resource_type  varchar(64),
  resource_id    varchar(128),
  payload        json,
  created_at     timestamp not null
);
```

事件类型：

```text
QUESTION_ASKED
CITATION_OPENED
DOCUMENT_IMPORTED
SEARCH_EXECUTED
```

建议索引：

```sql
create index idx_activity_user_created on activity_event(workspace_id, user_id, created_at);
create index idx_activity_type_created on activity_event(event_type, created_at);
```

### 4.5 app_setting

应用设置表，用于检索参数、外观偏好、能力开关等非密钥配置。

```sql
create table app_setting (
  id             varchar(64) primary key,
  workspace_id   varchar(64) not null default 'default',
  setting_key    varchar(128) not null,
  setting_value  json not null,
  version        int not null default 1,
  updated_by     varchar(64) not null default 'system',
  updated_at     timestamp not null
);
```

建议索引：

```sql
create unique index uk_app_setting_workspace_key on app_setting(workspace_id, setting_key);
```

### 4.6 provider_setting

Provider 设置表，用于模型、OCR、对象存储等配置。密钥不明文返回，接口只返回 masked value 和是否已配置。

```sql
create table provider_setting (
  id              varchar(64) primary key,
  workspace_id    varchar(64) not null default 'default',
  provider_type   varchar(64) not null,
  provider_name   varchar(128) not null,
  config_value    json not null,
  secret_ref      varchar(256),
  enabled         boolean not null default true,
  version         int not null default 1,
  updated_by      varchar(64) not null default 'system',
  updated_at      timestamp not null
);
```

建议索引：

```sql
create index idx_provider_workspace_type on provider_setting(workspace_id, provider_type);
```

## 5. P2 账号与企业表

P2 再补完整账号、权限和审计能力，P0/P1 只预留字段。

建议表：

```text
user_account
workspace
workspace_member
audit_log
provider_config_version
```

最小字段方向：

| 表 | 关键字段 | 说明 |
|---|---|---|
| `user_account` | `id`、`email`、`display_name`、`password_hash`、`status`、`external_subject` | 本地账号和外部身份映射 |
| `workspace` | `id`、`name`、`status`、`created_at` | 团队或租户 |
| `workspace_member` | `workspace_id`、`user_id`、`role`、`status` | OWNER / ADMIN / EDITOR / VIEWER |
| `audit_log` | `actor_id`、`action`、`resource_type`、`resource_id`、`payload`、`created_at` | 审计追踪 |
| `provider_config_version` | `provider_setting_id`、`version`、`config_snapshot`、`created_by` | 配置版本和回滚 |
