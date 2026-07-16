create table if not exists agent_run (
  run_id varchar(64) primary key,
  session_id varchar(64) not null,
  turn_id varchar(64) null,
  workflow_version varchar(64) not null,
  status varchar(32) not null,
  current_step varchar(64) null,
  step_count int not null default 0,
  tool_call_count int not null default 0,
  prompt_tokens int not null default 0,
  completion_tokens int not null default 0,
  latency_ms bigint not null default 0,
  fallback_reason varchar(128) null,
  error_code varchar(64) null,
  started_at datetime(3) not null,
  finished_at datetime(3) null,
  index idx_agent_run_session_started (session_id, started_at, run_id),
  index idx_agent_run_turn (turn_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists agent_step (
  step_id varchar(64) primary key,
  run_id varchar(64) not null,
  step_order int not null,
  step_type varchar(64) not null,
  attempt int not null default 1,
  status varchar(32) not null,
  decision_code varchar(64) null,
  input_summary json null,
  output_summary json null,
  prompt_tokens int not null default 0,
  completion_tokens int not null default 0,
  latency_ms bigint not null default 0,
  error_code varchar(64) null,
  created_at datetime(3) not null,
  constraint fk_agent_step_run foreign key (run_id) references agent_run(run_id) on delete cascade,
  unique key uk_agent_step_run_order (run_id, step_order)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists agent_task (
  task_id varchar(64) primary key,
  run_id varchar(64) not null,
  turn_id varchar(64) not null,
  session_id varchar(64) not null,
  user_id varchar(64) not null,
  task_type varchar(64) not null,
  status varchar(32) not null,
  progress int not null default 0,
  current_stage varchar(64) null,
  request_json json not null,
  answer mediumtext null,
  citations_json json null,
  attempt_count int not null default 0,
  next_retry_at datetime(3) null,
  lease_owner varchar(128) null,
  lease_until datetime(3) null,
  error_code varchar(64) null,
  error_message varchar(512) null,
  created_at datetime(3) not null,
  updated_at datetime(3) not null,
  started_at datetime(3) null,
  finished_at datetime(3) null,
  index idx_agent_task_claim (status, next_retry_at, lease_until, created_at),
  index idx_agent_task_session (session_id, created_at),
  index idx_agent_task_run (run_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

alter table conversation_turn
  modify column intent_type varchar(32) null default null,
  modify column intent_source varchar(16) null default null,
  add column agent_run_id varchar(64) null after retrieval_trace,
  add column workflow_version varchar(64) null after agent_run_id,
  add column execution_mode varchar(32) not null default 'TRADITIONAL' after workflow_version,
  add column agent_task_id varchar(64) null after execution_mode;

create index idx_conversation_turn_agent_run on conversation_turn(agent_run_id);
create index idx_conversation_turn_agent_task on conversation_turn(agent_task_id);
