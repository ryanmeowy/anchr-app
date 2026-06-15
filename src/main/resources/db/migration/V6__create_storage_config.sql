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
