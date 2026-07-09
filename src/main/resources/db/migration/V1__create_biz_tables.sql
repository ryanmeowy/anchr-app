create table if not exists knowledge_base (
  id bigint primary key,
  name varchar(128) not null,
  description text,
  status varchar(32) not null,
  document_count int not null default 0,
  segment_count int not null default 0,
  last_ingested_at timestamp null,
  created_by varchar(64) not null default 'system',
  updated_by varchar(64) not null default 'system',
  created_at timestamp not null,
  updated_at timestamp not null,
  deleted_at timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_kb_updated_at on knowledge_base(updated_at);

create table if not exists asset (
  id bigint primary key,
  kb_id bigint not null,
  file_name varchar(512) not null,
  title varchar(512),
  file_type varchar(32) not null,
  mime_type varchar(128),
  size_bytes bigint,
  file_hash varchar(128),
  version_group_id varchar(64) null,
  version_no int not null default 1,
  previous_asset_id varchar(64) null,
  object_key varchar(1024),
  preview_object_key varchar(1024),
  thumbnail_key varchar(1024),
  source_url text,
  parse_status varchar(32) not null,
  index_status varchar(32) not null,
  segment_count int not null default 0,
  indexed_segment_count int not null default 0,
  embedding_profile varchar(128),
  error_code varchar(128),
  error_message text,
  created_by varchar(64) not null default 'system',
  updated_by varchar(64) not null default 'system',
  created_at timestamp not null,
  updated_at timestamp not null,
  deleted_at timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_doc_kb_status on asset(kb_id, parse_status, index_status);
create index idx_doc_hash on asset(kb_id, file_hash);
create index idx_doc_created_at on asset(kb_id, created_at);
create index idx_doc_version_group on asset(kb_id, version_group_id, version_no);

create table if not exists ingestion_task (
  id bigint primary key,
  kb_id bigint not null,
  source_type varchar(32) not null,
  status varchar(32) not null,
  total_count int not null default 0,
  success_count int not null default 0,
  failure_count int not null default 0,
  running_count int not null default 0,
  created_by varchar(64) not null default 'system',
  updated_by varchar(64) not null default 'system',
  created_at timestamp not null,
  updated_at timestamp not null,
  finished_at timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_task_kb_created on ingestion_task(kb_id, created_at);
create index idx_task_status on ingestion_task(status);

create table if not exists ingestion_task_item (
  id bigint primary key,
  task_id bigint not null,
  kb_id bigint not null,
  asset_id varchar(64),
  file_name varchar(512),
  file_hash varchar(128),
  source_url text,
  stage varchar(32) not null,
  status varchar(32) not null,
  progress int not null default 0,
  dedupe_strategy varchar(32) null,
  dedupe_result varchar(32),
  duplicate_asset_id varchar(64) null,
  error_code varchar(128),
  error_message text,
  created_at timestamp not null,
  updated_at timestamp not null,
  finished_at timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_task_item_task on ingestion_task_item(task_id);
create index idx_task_item_asset on ingestion_task_item(asset_id);
create index idx_task_item_kb_status on ingestion_task_item(kb_id, status);

create table if not exists activity_event (
  id bigint primary key,
  user_id varchar(32) not null default 'system',
  event_type varchar(64) not null,
  resource_type varchar(64),
  resource_id varchar(128),
  payload json,
  created_at timestamp not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_activity_type_created on activity_event(event_type, created_at);

create table if not exists capability_config (
    id bigint primary key,
    capability      varchar(32) not null,
    base_url        varchar(512) not null,
    api_key_enc     varchar(512) not null,
    model_name      varchar(128),
    extra_config    json,
    enabled         boolean not null default false,
    updated_by varchar(64) not null default 'system',
    updated_at      timestamp not null,
    deleted_at      timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists storage_config (
    id bigint primary key,
    endpoint        varchar(512) not null,
    access_key_enc  varchar(512) not null,
    secret_key_enc  varchar(512) not null,
    bucket          varchar(256) not null,
    region          varchar(64),
    prefix          varchar(256),
    role_arn        varchar(256),
    enabled         boolean not null default true,
    updated_by varchar(64) not null default 'system',
    updated_at      timestamp not null,
    deleted_at      timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
