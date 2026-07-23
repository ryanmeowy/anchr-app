-- Kept separate from V11 because MySQL DDL implicitly commits.
alter table ingestion_task_item
    add column current_execution_id bigint null after task_id,
    add index idx_ingestion_item_current_execution (current_execution_id, id),
    add constraint chk_ingestion_item_public_stage check (
        stage in ('UPLOAD', 'PARSE', 'CHUNK', 'EMBED', 'INDEX', 'ASKABLE')
    ),
    add constraint chk_ingestion_item_public_status check (
        status in ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')
    ),
    add constraint chk_ingestion_item_public_progress check (
        progress between 0 and 100
    );
