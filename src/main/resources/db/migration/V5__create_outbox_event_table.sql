create table if not exists outbox_event (
    id bigint primary key auto_increment comment 'Outbox 事件主键',
    event_type varchar(64) not null comment '事件类型',
    aggregate_type varchar(64) not null comment '聚合根类型',
    aggregate_id bigint not null comment '聚合根主键',
    payload json not null comment '事件载荷',
    status varchar(20) not null default 'PENDING' comment '处理状态',
    retry_count int not null default 0 comment '重试次数',
    next_retry_at datetime null comment '下次重试时间',
    lock_token varchar(64) null comment '处理锁令牌',
    locked_at datetime null comment '加锁时间',
    processed_at datetime null comment '处理完成时间',
    last_error text null comment '最近一次错误',
    created_by varchar(64) not null comment '创建人',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    index idx_outbox_poll (status, next_retry_at, id),
    index idx_outbox_locked (status, locked_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='事务消息发件箱';
