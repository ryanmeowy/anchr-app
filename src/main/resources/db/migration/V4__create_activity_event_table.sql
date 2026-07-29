create table if not exists activity_event (
    id bigint primary key comment '活动事件主键',
    user_id varchar(32) not null default 'system' comment '用户标识',
    event_type varchar(64) not null comment '事件类型',
    resource_type varchar(64) comment '资源类型',
    resource_id varchar(128) comment '资源标识',
    payload json comment '事件载荷',
    created_at timestamp not null comment '创建时间',
    index idx_activity_type_created (event_type, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='用户活动事件';
