create table if not exists capability_config (
  id bigint primary key,
  capability      varchar(32) not null,
  base_url        varchar(512) not null,
  api_key_enc     varchar(512) not null,
  model_name      varchar(128),
  image_model     varchar(128),
  image_endpoint  varchar(512),
  extra_config    json,
  enabled         boolean not null default true,
  updated_by bigint not null default 0,
  updated_at      timestamp not null,
  unique key uk_capability (capability)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
