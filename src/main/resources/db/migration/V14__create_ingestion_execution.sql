create table if not exists ingestion_item_execution (
    id bigint auto_increment primary key,
    item_id bigint not null,
    execution_epoch bigint not null,
    execution_kind varchar(32) character set ascii collate ascii_bin not null,
    execution_status varchar(16) character set ascii collate ascii_bin not null,
    phase varchar(32) character set ascii collate ascii_bin not null,
    parse_attempt_id bigint null,
    claim_version bigint not null default 0,
    phase_retry_count int not null default 0,
    phase_started_at datetime(6) null,
    next_action_at datetime(6) null,
    lease_token varchar(64) character set ascii collate ascii_bin null,
    lease_until datetime(6) null,
    error_code varchar(128) null,
    error_message text null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    finished_at datetime(6) null,
    constraint uk_ingestion_execution_item_epoch unique (item_id, execution_epoch),
    constraint uk_ingestion_execution_id_item unique (id, item_id),
    index idx_ingestion_execution_claim (
        execution_status, next_action_at, lease_until, id
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
