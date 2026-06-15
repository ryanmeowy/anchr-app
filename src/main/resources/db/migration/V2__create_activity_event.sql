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
    updated_at      timestamp not null
    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
