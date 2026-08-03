create table if not exists conversation_session (
    session_id varchar(64) primary key comment '会话标识',
    user_id varchar(64) not null comment '用户标识',
    title varchar(128) comment '会话标题',
    status varchar(32) not null comment '会话状态',
    kb_scope json comment '知识库范围',
    asset_scope json comment '资产范围',
    created_at datetime(3) not null comment '创建时间',
    updated_at datetime(3) not null comment '更新时间',
    deleted_at datetime(3) null comment '软删除时间',
    index idx_conversation_session_user_updated (user_id, updated_at, session_id),
    index idx_conversation_session_user_active_updated (
        user_id, deleted_at, updated_at, session_id
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='问答会话';

create table if not exists conversation_turn (
    turn_id varchar(64) primary key comment '对话轮次标识',
    session_id varchar(64) not null comment '所属会话标识',
    query varchar(1000) comment '用户问题',
    rewritten_query text comment '改写后的检索问题',
    answer longtext comment '回答内容',
    kb_scope json comment '知识库范围快照',
    asset_scope json comment '资产范围快照',
    answer_mode varchar(32) comment '回答模式',
    answer_status varchar(32) comment '回答状态',
    answer_fallback_reason varchar(128) comment '回答降级原因',
    intent_type varchar(32) null default null comment '意图类型',
    intent_confidence decimal(5,4) null comment '意图置信度',
    intent_reason varchar(255) null comment '意图判断原因',
    intent_source varchar(16) null default null comment '意图判断来源',
    intent_fallback boolean not null default false comment '是否使用意图降级',
    citations json comment '引用信息',
    result_cards json comment '结果卡片',
    retrieval_trace json comment '检索过程追踪',
    agent_run_id varchar(64) null comment 'Agent 运行标识',
    execution_mode varchar(32) not null default 'TRADITIONAL' comment '执行模式',
    agent_task_id varchar(64) null comment 'Agent 任务标识',
    created_at datetime(3) not null comment '创建时间',
    deleted_at datetime(3) null comment '软删除时间',
    index idx_conversation_turn_session_created (session_id, created_at, turn_id),
    index idx_conversation_turn_session_active_created (
        session_id, deleted_at, created_at, turn_id
    ),
    index idx_conversation_turn_agent_run (agent_run_id),
    index idx_conversation_turn_agent_task (agent_task_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='问答会话轮次';
