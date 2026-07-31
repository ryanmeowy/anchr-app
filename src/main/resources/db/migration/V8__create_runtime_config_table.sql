create table if not exists runtime_config (
    type varchar(32) not null comment '运行配置分区',
    param_key varchar(128) not null comment '参数键',
    param_value varchar(2048) not null comment '参数当前值',
    updated_by varchar(64) not null default 'system' comment '更新人',
    updated_at datetime(3) not null comment '更新时间',
    primary key (type, param_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='运行参数当前值';
