create table if not exists knowledge_base (
    id bigint primary key comment '知识库主键',
    name varchar(128) not null comment '知识库名称',
    description text comment '知识库描述',
    status varchar(32) not null comment '知识库状态',
    document_count int not null default 0 comment '文档数量',
    segment_count int not null default 0 comment '分段数量',
    last_ingested_at timestamp null comment '最近摄取完成时间',
    created_by varchar(64) not null default 'system' comment '创建人',
    updated_by varchar(64) not null default 'system' comment '更新人',
    created_at timestamp not null comment '创建时间',
    updated_at timestamp not null comment '更新时间',
    deleted_at timestamp null comment '软删除时间',
    index idx_kb_updated_at (updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='知识库';

create table if not exists asset (
    id bigint primary key comment '资产主键',
    kb_id bigint not null comment '所属知识库主键',
    file_name varchar(512) not null comment '原始文件名',
    title varchar(512) comment '资产标题',
    file_type varchar(32) not null comment '文件类型',
    mime_type varchar(128) comment '媒体类型',
    size_bytes bigint comment '文件大小（字节）',
    file_hash varchar(128) comment '文件内容哈希',
    version_group_id varchar(64) null comment '版本组标识',
    version_no int not null default 1 comment '版本号',
    object_key varchar(1024) not null comment '原始文件对象键',
    preview_object_key varchar(1024) comment '预览文件对象键',
    parse_status varchar(32) not null comment '解析状态',
    index_status varchar(32) not null comment '索引状态',
    segment_count int not null default 0 comment '分段总数',
    indexed_segment_count int not null default 0 comment '已索引分段数',
    active_index_generation bigint not null default 0 comment '当前生效的索引代次',
    error_code varchar(128) comment '错误码',
    error_message text comment '错误信息',
    created_by varchar(64) not null default 'system' comment '创建人',
    updated_by varchar(64) not null default 'system' comment '更新人',
    created_at timestamp not null comment '创建时间',
    updated_at timestamp not null comment '更新时间',
    deleted_at timestamp null comment '软删除时间',
    index idx_doc_kb_status (kb_id, parse_status, index_status),
    index idx_doc_hash (kb_id, file_hash),
    index idx_doc_created_at (kb_id, created_at),
    index idx_doc_version_group (kb_id, version_group_id, version_no)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='知识库资产';
