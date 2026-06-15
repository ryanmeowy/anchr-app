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
