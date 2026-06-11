create table if not exists app_setting (
  id             varchar(64) primary key,
  setting_key    varchar(128) not null,
  setting_value  json not null,
  version        int not null default 1,
  updated_by     varchar(64) not null default 'system',
  updated_at     timestamp not null,
  unique key uk_app_setting_key (setting_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
