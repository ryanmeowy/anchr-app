alter table conversation_turn
  add column intent_type varchar(32) not null default 'KB_QUERY' after answer_fallback_reason,
  add column intent_confidence decimal(5,4) null after intent_type,
  add column intent_reason varchar(255) null after intent_confidence,
  add column intent_source varchar(16) not null default 'LEGACY' after intent_reason,
  add column intent_fallback boolean not null default false after intent_source;
