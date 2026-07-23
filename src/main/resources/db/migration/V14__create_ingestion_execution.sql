create table ingestion_item_execution (
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
    constraint chk_ingestion_execution_epoch_v11_positive check (execution_epoch >= 1),
    constraint chk_ingestion_execution_kind check (
        execution_kind in ('INITIAL', 'REPARSE', 'REEMBED', 'EXPLICIT_RETRY')
    ),
    constraint chk_ingestion_execution_status check (
        execution_status in ('ACTIVE', 'SUCCEEDED', 'FAILED')
    ),
    constraint chk_ingestion_execution_phase check (
        phase in ('PARSE_SUBMIT', 'PARSE_WAIT', 'PARSE_PERSIST', 'EMBED', 'INDEX')
    ),
    constraint chk_ingestion_claim_version_nonnegative check (claim_version >= 0),
    constraint chk_ingestion_phase_retry_nonnegative check (phase_retry_count >= 0),
    constraint chk_ingestion_execution_lease_pair check (
        (lease_token is null and lease_until is null)
        or (lease_token is not null and lease_until is not null)
    ),
    constraint chk_ingestion_execution_terminal_time check (
        (execution_status = 'ACTIVE' and finished_at is null)
        or (execution_status in ('SUCCEEDED', 'FAILED') and finished_at is not null)
    ),
    constraint fk_ingestion_execution_item
        foreign key (item_id) references ingestion_task_item (id)
        on update restrict on delete restrict,
    constraint fk_ingestion_execution_parse_attempt
        foreign key (parse_attempt_id, item_id)
        references ingestion_item_parse_attempt (id, item_id)
        on update restrict on delete restrict,
    index idx_ingestion_execution_claim (
        execution_status, next_action_at, lease_until, id
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
