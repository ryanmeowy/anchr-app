create table if not exists activity_event (
  id varchar(64) primary key,
  workspace_id varchar(64) not null default 'default',
  user_id varchar(64) not null default 'system',
  event_type varchar(64) not null,
  resource_type varchar(64),
  resource_id varchar(128),
  payload json,
  created_at timestamp not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_activity_user_created on activity_event(workspace_id, user_id, created_at);
create index idx_activity_type_created on activity_event(event_type, created_at);
