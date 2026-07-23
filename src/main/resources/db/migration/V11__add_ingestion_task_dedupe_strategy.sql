-- ANCHR-106B preflight plus the first expand DDL. MySQL DDL implicitly commits,
-- so subsequent tables are deliberately split into their own Flyway versions.
create temporary table tmp_ingestion_v11_guard (
    id tinyint primary key,
    violation_count bigint not null,
    constraint chk_tmp_ingestion_v11_guard_clean check (violation_count = 0)
);

insert into tmp_ingestion_v11_guard (id, violation_count)
select 1,
       (select count(*)
        from ingestion_task_item iti
        left join ingestion_task it on it.id = iti.task_id
        where it.id is null
           or iti.kb_id <> it.kb_id)
       +
       (select count(*)
        from (
            select task_id
            from ingestion_task_item
            group by task_id
            having count(distinct coalesce(dedupe_strategy, '__NULL__')) > 1
        ) inconsistent_strategy)
       +
       (select count(*)
        from ingestion_task_item
        where embedding_result_object_key is not null
          and parse_result_object_key is null)
       +
       (select count(*)
        from ingestion_task_item
        where stage not in ('UPLOAD', 'PARSE', 'CHUNK', 'EMBED', 'INDEX', 'ASKABLE')
           or status not in ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')
           or progress < 0
           or progress > 100)
       +
       (select count(*)
        from ingestion_task_item
        where (status in ('PENDING', 'RUNNING')
               and execution_stage in ('COMPLETE', 'FAILED'))
           or (status in ('SUCCESS', 'SKIPPED')
               and execution_stage <> 'COMPLETE')
           or (status = 'FAILED'
               and execution_stage <> 'FAILED'))
       +
       (select count(*)
        from (
            select docling_request_id
            from ingestion_task_item
            where docling_request_id is not null
            group by docling_request_id
            having count(*) > 1
        ) duplicate_request);

drop temporary table tmp_ingestion_v11_guard;

alter table ingestion_task
    add column dedupe_strategy varchar(32) character set ascii collate ascii_bin null
        after request_hash,
    add constraint chk_ingestion_task_dedupe_strategy
        check (dedupe_strategy is null
            or dedupe_strategy in ('SKIP', 'OVERWRITE', 'VERSIONED'));
