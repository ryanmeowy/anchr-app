create table if not exists app_setting (
  id             varchar(64) primary key,
  workspace_id   varchar(64) not null default 'default',
  setting_key    varchar(128) not null,
  setting_value  json not null,
  version        int not null default 1,
  updated_by     varchar(64) not null default 'system',
  updated_at     timestamp not null,
  unique key uk_app_setting_workspace_key (workspace_id, setting_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists provider_setting (
  id              varchar(64) primary key,
  workspace_id    varchar(64) not null default 'default',
  provider_type   varchar(64) not null,
  provider_name   varchar(128) not null,
  config_value    json not null,
  secret_ref      varchar(256),
  enabled         boolean not null default true,
  version         int not null default 1,
  updated_by      varchar(64) not null default 'system',
  updated_at      timestamp not null,
  unique key uk_provider_workspace_type_name (workspace_id, provider_type, provider_name),
  key idx_provider_workspace_type (workspace_id, provider_type)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists provider_config_version (
  id                   varchar(64) primary key,
  provider_setting_id  varchar(64) not null,
  version              int not null,
  config_snapshot      json not null,
  created_by           varchar(64) not null default 'system',
  created_at           timestamp not null,
  key idx_provider_config_setting_version (provider_setting_id, version)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
