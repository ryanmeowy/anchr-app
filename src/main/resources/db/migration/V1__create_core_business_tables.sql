create table if not exists knowledge_base (
  id varchar(64) primary key,
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

create table if not exists document_asset (
  id varchar(64) primary key,
  kb_id varchar(64) not null,
  file_name varchar(512) not null,
  title varchar(512),
  file_type varchar(32) not null,
  mime_type varchar(128),
  size_bytes bigint,
  file_hash varchar(128),
  object_key varchar(1024),
  preview_object_key varchar(1024),
  thumbnail_key varchar(1024),
  source_url text,
  parse_status varchar(32) not null,
  index_status varchar(32) not null,
  segment_count int not null default 0,
  embedding_profile varchar(128),
  error_code varchar(128),
  error_message text,
  created_by varchar(64) not null default 'system',
  updated_by varchar(64) not null default 'system',
  created_at timestamp not null,
  updated_at timestamp not null,
  deleted_at timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_doc_kb_status on document_asset(kb_id, parse_status, index_status);
create index idx_doc_hash on document_asset(kb_id, file_hash);
create index idx_doc_created_at on document_asset(kb_id, created_at);

create table if not exists ingestion_task (
  id varchar(64) primary key,
  kb_id varchar(64) not null,
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
  id varchar(64) primary key,
  task_id varchar(64) not null,
  kb_id varchar(64) not null,
  asset_id varchar(64),
  file_name varchar(512),
  file_hash varchar(128),
  source_url text,
  stage varchar(32) not null,
  status varchar(32) not null,
  progress int not null default 0,
  dedupe_result varchar(32),
  error_code varchar(128),
  error_message text,
  created_at timestamp not null,
  updated_at timestamp not null,
  finished_at timestamp null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_task_item_task on ingestion_task_item(task_id);
create index idx_task_item_asset on ingestion_task_item(asset_id);
create index idx_task_item_kb_status on ingestion_task_item(kb_id, status);
