create table if not exists conversation_session (
  session_id varchar(64) primary key,
  user_id varchar(64) not null,
  title varchar(128),
  status varchar(32) not null,
  kb_scope json,
  asset_scope json,
  created_at datetime(3) not null,
  updated_at datetime(3) not null,
  deleted_at datetime(3) null,
  index idx_conversation_session_user_updated (user_id, updated_at, session_id),
  index idx_conversation_session_user_active_updated (user_id, deleted_at, updated_at, session_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists conversation_turn (
  turn_id varchar(64) primary key,
  session_id varchar(64) not null,
  query varchar(1000),
  rewritten_query text,
  answer longtext,
  kb_scope json,
  asset_scope json,
  answer_mode varchar(32),
  answer_status varchar(32),
  answer_fallback_reason varchar(128),
  intent_type varchar(32) null default null,
  intent_confidence decimal(5,4) null,
  intent_reason varchar(255) null,
  intent_source varchar(16) null default null,
  intent_fallback boolean not null default false,
  citations json,
  result_cards json,
  retrieval_trace json,
  agent_run_id varchar(64) null,
  workflow_version varchar(64) null,
  execution_mode varchar(32) not null default 'TRADITIONAL',
  agent_task_id varchar(64) null,
  created_at datetime(3) not null,
  deleted_at datetime(3) null,
  index idx_conversation_turn_session_created (session_id, created_at, turn_id),
  index idx_conversation_turn_session_active_created (
    session_id, deleted_at, created_at, turn_id
  ),
  index idx_conversation_turn_agent_run (agent_run_id),
  index idx_conversation_turn_agent_task (agent_task_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
