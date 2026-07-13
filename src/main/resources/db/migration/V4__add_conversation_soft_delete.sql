alter table conversation_session
  add column deleted_at datetime(3) null after updated_at;

alter table conversation_turn
  add column deleted_at datetime(3) null after created_at;

create index idx_conversation_session_user_active_updated
  on conversation_session(user_id, deleted_at, updated_at, session_id);

create index idx_conversation_turn_session_active_created
  on conversation_turn(session_id, deleted_at, created_at, turn_id);
