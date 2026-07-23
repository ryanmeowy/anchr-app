alter table ingestion_task_item
    add column execution_stage varchar(32) character set ascii collate ascii_bin
        not null default 'PARSE_SUBMIT' after source_revision,
    add column execution_epoch bigint not null default 1 after execution_stage,
    add column stage_attempt int not null default 0 after execution_epoch,
    add column stage_retry_count int not null default 0 after stage_attempt,
    add column stage_started_at datetime(6) null after stage_retry_count,
    add column next_action_at datetime(6) null after stage_started_at,
    add column lease_token varchar(64) character set ascii collate ascii_bin null after next_action_at,
    add column lease_until datetime(6) null after lease_token,
    add column parse_request_snapshot json null after lease_until,
    add column parse_result_object_key varchar(1024) character set utf8mb4 collate utf8mb4_bin null
        after parse_request_snapshot,
    add column embedding_result_object_key varchar(1024) character set utf8mb4 collate utf8mb4_bin null
        after parse_result_object_key,
    add constraint chk_ingestion_execution_stage
        check (execution_stage in (
            'PARSE_SUBMIT', 'PARSE_WAIT', 'PARSE_PERSIST', 'EMBED', 'INDEX', 'COMPLETE', 'FAILED'
        )),
    add constraint chk_ingestion_execution_epoch_positive check (execution_epoch >= 1),
    add constraint chk_ingestion_stage_attempt_nonnegative check (stage_attempt >= 0),
    add constraint chk_ingestion_stage_retry_nonnegative check (stage_retry_count >= 0),
    add constraint chk_ingestion_lease_pair check (
        (lease_token is null and lease_until is null)
        or (lease_token is not null and lease_until is not null)
    ),
    add index idx_ingestion_item_claim (
        status, next_action_at, lease_until, id
    ),
    add index idx_ingestion_task_claim (
        task_id, status, next_action_at, lease_until, id
    );

update ingestion_task_item
set execution_stage = case
        when status in ('SUCCESS', 'SKIPPED') then 'COMPLETE'
        when status = 'FAILED' then 'FAILED'
        else 'PARSE_SUBMIT'
    end,
    stage = case
        when status in ('SUCCESS', 'SKIPPED', 'FAILED') then stage
        else 'PARSE'
    end,
    progress = case
        when status in ('SUCCESS', 'SKIPPED', 'FAILED') then progress
        else 20
    end,
    execution_epoch = 1,
    stage_attempt = 0,
    stage_retry_count = 0,
    stage_started_at = null,
    next_action_at = case
        when status in ('SUCCESS', 'SKIPPED', 'FAILED') then null
        else coalesce(updated_at, created_at, current_timestamp(6))
    end,
    lease_token = null,
    lease_until = null,
    parse_request_snapshot = null,
    parse_result_object_key = null,
    embedding_result_object_key = null;
