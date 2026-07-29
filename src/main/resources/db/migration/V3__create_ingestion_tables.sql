create table if not exists ingestion_task (
    id bigint primary key comment '摄取任务主键',
    kb_id bigint not null comment '目标知识库主键',
    source_type varchar(32) character set ascii collate ascii_bin not null
        comment '任务来源类型',
    client_request_id varchar(128) character set utf8mb4 collate utf8mb4_bin null
        comment '调用方幂等请求标识',
    request_hash varchar(80) character set ascii collate ascii_bin null
        comment '稳定请求指纹',
    dedupe_strategy varchar(32) character set ascii collate ascii_bin null
        comment '任务内统一去重策略',
    status varchar(32) character set ascii collate ascii_bin not null
        comment '任务汇总状态',
    total_count int not null default 0 comment '条目总数',
    success_count int not null default 0 comment '成功或跳过条目数',
    failure_count int not null default 0 comment '失败条目数',
    running_count int not null default 0 comment '运行中条目数',
    created_by varchar(64) not null default 'system' comment '创建人',
    updated_by varchar(64) not null default 'system' comment '更新人',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '更新时间',
    finished_at datetime(6) null comment '完成时间',
    unique key uk_ingestion_task_creator_request (created_by, client_request_id),
    index idx_ingestion_task_kb_created (kb_id, created_at, id),
    index idx_ingestion_task_status (status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='知识库摄取任务';

create table if not exists ingestion_task_item (
    id bigint primary key comment '摄取任务条目主键',
    task_id bigint not null comment '所属摄取任务主键',
    asset_id varchar(64) null comment '目标资产标识',
    target_index_generation bigint null comment '本次写入的目标索引代次',
    file_name varchar(512) null comment '来源文件名',
    file_hash varchar(128) null comment '来源文件内容哈希',

    stage varchar(32) character set ascii collate ascii_bin not null
        comment '对外展示阶段',
    status varchar(32) character set ascii collate ascii_bin not null
        comment '条目处理状态',
    progress int not null default 0 comment '对外处理进度百分比',
    dedupe_result varchar(32) character set ascii collate ascii_bin null
        comment '去重判定结果',
    duplicate_asset_id varchar(64) null comment '命中的重复资产标识',
    error_code varchar(128) null comment '最后一次错误码',
    error_message text null comment '最后一次错误信息',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '更新时间',
    finished_at datetime(6) null comment '完成时间',

    index idx_ingestion_item_pending (status, id),
    index idx_ingestion_item_task_pending (task_id, status, id),
    index idx_ingestion_item_asset_generation (asset_id, target_index_generation),
    index idx_ingestion_item_task (task_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='摄取任务条目';
