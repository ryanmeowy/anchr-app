create table if not exists conversation_session (
  session_id varchar(64) primary key,
  user_id varchar(64) not null,
  title varchar(128),
  status varchar(32) not null,
  kb_scope json,
  asset_scope json,
  created_at datetime(3) not null,
  updated_at datetime(3) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_conversation_session_user_updated
  on conversation_session(user_id, updated_at, session_id);

create table if not exists conversation_turn (
  turn_id varchar(64) primary key,
  session_id varchar(64) not null,
  role varchar(32) not null,
  query varchar(1000),
  rewritten_query text,
  answer longtext,
  kb_scope json,
  asset_scope json,
  answer_mode varchar(32),
  answer_status varchar(32),
  answer_fallback_reason varchar(128),
  citations json,
  result_cards json,
  retrieval_trace json,
  created_at datetime(3) not null,
  constraint fk_conversation_turn_session
    foreign key (session_id) references conversation_session(session_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create index idx_conversation_turn_session_created
  on conversation_turn(session_id, created_at, turn_id);
