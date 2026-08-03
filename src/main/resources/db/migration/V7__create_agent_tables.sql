create table if not exists agent_run (
    run_id varchar(64) primary key comment 'Agent 运行标识',
    session_id varchar(64) not null comment '会话标识',
    turn_id varchar(64) null comment '对话轮次标识',
    status varchar(32) not null comment '运行状态',
    current_step varchar(64) null comment '当前步骤',
    step_count int not null default 0 comment '步骤数量',
    tool_call_count int not null default 0 comment '工具调用次数',
    prompt_tokens int not null default 0 comment '输入 Token 数量',
    completion_tokens int not null default 0 comment '输出 Token 数量',
    latency_ms bigint not null default 0 comment '运行耗时（毫秒）',
    fallback_reason varchar(128) null comment '降级原因',
    error_code varchar(64) null comment '错误码',
    started_at datetime(3) not null comment '开始时间',
    finished_at datetime(3) null comment '完成时间',
    index idx_agent_run_session_started (session_id, started_at, run_id),
    index idx_agent_run_turn (turn_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='Agent 运行记录';

create table if not exists agent_step (
    step_id varchar(64) primary key comment 'Agent 步骤标识',
    run_id varchar(64) not null comment 'Agent 运行标识',
    step_order int not null comment '步骤顺序',
    step_type varchar(64) not null comment '步骤类型',
    attempt int not null default 1 comment '尝试次数',
    status varchar(32) not null comment '步骤状态',
    decision_code varchar(64) null comment '决策编码',
    input_summary json null comment '输入摘要',
    output_summary json null comment '输出摘要',
    prompt_tokens int not null default 0 comment '输入 Token 数量',
    completion_tokens int not null default 0 comment '输出 Token 数量',
    latency_ms bigint not null default 0 comment '步骤耗时（毫秒）',
    error_code varchar(64) null comment '错误码',
    created_at datetime(3) not null comment '创建时间',
    unique key uk_agent_step_run_order (run_id, step_order)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='Agent 运行步骤';

create table if not exists agent_task (
    task_id varchar(64) primary key comment 'Agent 任务标识',
    run_id varchar(64) not null comment 'Agent 运行标识',
    turn_id varchar(64) not null comment '对话轮次标识',
    session_id varchar(64) not null comment '会话标识',
    user_id varchar(64) not null comment '用户标识',
    task_type varchar(64) not null comment '任务类型',
    status varchar(32) not null comment '任务状态',
    progress int not null default 0 comment '任务进度百分比',
    current_stage varchar(64) null comment '当前任务阶段',
    request_json json not null comment '任务请求快照',
    answer mediumtext null comment '回答内容',
    citations_json json null comment '引用信息',
    attempt_count int not null default 0 comment '尝试次数',
    next_retry_at datetime(3) null comment '下次重试时间',
    lease_owner varchar(128) null comment '租约持有者',
    lease_until datetime(3) null comment '租约到期时间',
    error_code varchar(64) null comment '错误码',
    error_message varchar(512) null comment '错误信息',
    created_at datetime(3) not null comment '创建时间',
    updated_at datetime(3) not null comment '更新时间',
    started_at datetime(3) null comment '开始时间',
    finished_at datetime(3) null comment '完成时间',
    index idx_agent_task_claim (status, next_retry_at, lease_until, created_at),
    index idx_agent_task_session (session_id, created_at),
    index idx_agent_task_run (run_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='异步 Agent 任务';
