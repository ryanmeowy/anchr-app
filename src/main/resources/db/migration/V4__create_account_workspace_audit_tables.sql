create table if not exists user_account (
  id                varchar(64) primary key,
  email             varchar(256) not null,
  display_name      varchar(128) not null,
  password_hash     varchar(512),
  status            varchar(32) not null default 'ACTIVE',
  external_issuer   varchar(256),
  external_subject  varchar(256),
  created_at        timestamp not null,
  updated_at        timestamp not null,
  unique key uk_user_account_email (email),
  unique key uk_user_account_external (external_issuer, external_subject)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists workspace (
  id          varchar(64) primary key,
  name        varchar(128) not null,
  status      varchar(32) not null default 'ACTIVE',
  created_by  varchar(64) not null default 'system',
  updated_by  varchar(64) not null default 'system',
  created_at  timestamp not null,
  updated_at  timestamp not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists workspace_member (
  workspace_id  varchar(64) not null,
  user_id       varchar(64) not null,
  role          varchar(32) not null,
  status        varchar(32) not null default 'ACTIVE',
  created_by    varchar(64) not null default 'system',
  updated_by    varchar(64) not null default 'system',
  created_at    timestamp not null,
  updated_at    timestamp not null,
  primary key (workspace_id, user_id),
  key idx_workspace_member_user (user_id, status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
