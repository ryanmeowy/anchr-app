alter table ingestion_task_item
    add column parse_attempt int not null default 1 after source_url,
    add column docling_request_id varchar(200) character set utf8mb4 collate utf8mb4_bin null after parse_attempt,
    add column docling_job_id varchar(64) character set ascii collate ascii_bin null after docling_request_id,
    add column source_revision varchar(80) character set ascii collate ascii_bin null after docling_job_id,
    add constraint chk_ingestion_parse_attempt_positive check (parse_attempt >= 1);
