create table if not exists capability_config (
    id bigint primary key comment '主键',
    capability varchar(32) not null comment '能力类型',
    base_url varchar(512) not null comment '服务基础地址',
    api_key_enc varchar(512) not null comment '加密后的 API 密钥',
    model_name varchar(128) comment '模型名称',
    extra_config json comment '扩展配置',
    enabled boolean not null default false comment '是否启用',
    updated_by varchar(64) not null default 'system' comment '更新人',
    updated_at timestamp not null comment '更新时间',
    deleted_at timestamp null comment '软删除时间'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
  comment='模型能力配置';

create table if not exists storage_config (
    id bigint primary key comment '主键',
    endpoint varchar(512) not null comment '对象存储服务地址',
    access_key_enc varchar(512) not null comment '加密后的访问密钥标识',
    secret_key_enc varchar(512) not null comment '加密后的访问密钥',
    bucket varchar(256) not null comment '存储桶名称',
    region varchar(64) comment '存储区域',
    prefix varchar(256) comment '对象键基础前缀',
    role_arn varchar(256) comment '角色 ARN',
    enabled boolean not null default true comment '是否启用',
    updated_by varchar(64) not null default 'system' comment '更新人',
    updated_at timestamp not null comment '更新时间',
    deleted_at timestamp null comment '软删除时间'
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
  comment='对象存储配置';
