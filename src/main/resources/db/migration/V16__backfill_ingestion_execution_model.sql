-- Re-run the guard immediately before backfill. Deployment must freeze all
-- ingestion writers while V11-V16 run; this catches a late legacy write.
create temporary table tmp_ingestion_v16_guard (
    id tinyint primary key,
    violation_count bigint not null,
    constraint chk_tmp_ingestion_v16_guard_clean check (violation_count = 0)
);

insert into tmp_ingestion_v16_guard (id, violation_count)
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

drop temporary table tmp_ingestion_v16_guard;

update ingestion_task it
left join (
    select task_id, max(dedupe_strategy) as dedupe_strategy
    from ingestion_task_item
    group by task_id
) item_strategy on item_strategy.task_id = it.id
set it.dedupe_strategy = item_strategy.dedupe_strategy;

-- SKIPPED and request-validation failures deliberately keep no execution.
insert into ingestion_item_parse_attempt (
    id, item_id, attempt_no, status, request_id, job_id, source_revision,
    request_snapshot, created_at, updated_at, finished_at
)
select iti.id,
       iti.id,
       greatest(iti.parse_attempt, 1),
       case
           when iti.parse_result_object_key is not null
               or iti.status = 'SUCCESS'
               or iti.execution_stage in ('EMBED', 'INDEX', 'COMPLETE')
               or iti.stage in ('EMBED', 'INDEX', 'ASKABLE')
               then 'SUCCEEDED'
           when iti.status = 'FAILED' then 'FAILED'
           else 'ACTIVE'
       end,
       iti.docling_request_id,
       iti.docling_job_id,
       iti.source_revision,
       iti.parse_request_snapshot,
       iti.created_at,
       iti.updated_at,
       case
           when iti.status = 'SUCCESS'
               or iti.parse_result_object_key is not null
               or iti.execution_stage in ('EMBED', 'INDEX', 'COMPLETE')
               or iti.stage in ('EMBED', 'INDEX', 'ASKABLE')
               then iti.updated_at
           when iti.status = 'FAILED' then coalesce(iti.finished_at, iti.updated_at)
           else null
       end
from ingestion_task_item iti
where iti.status in ('PENDING', 'RUNNING', 'SUCCESS')
   or (
       iti.status = 'FAILED'
       and (
           iti.asset_id is not null
           or iti.docling_request_id is not null
           or iti.parse_result_object_key is not null
           or iti.embedding_result_object_key is not null
       )
   );

-- V10 did not persist execution intent separately. Dedicated maintenance
-- tasks carry no dedupe strategy, while the generic create API normalizes one;
-- source_type alone therefore cannot distinguish their execution kind.
insert into ingestion_item_execution (
    id, item_id, execution_epoch, execution_kind, execution_status, phase,
    parse_attempt_id, claim_version, phase_retry_count, phase_started_at,
    next_action_at, lease_token, lease_until, error_code, error_message,
    created_at, updated_at, finished_at
)
select iti.id,
       iti.id,
       greatest(iti.execution_epoch, 1),
       case
           when iti.execution_epoch > 1 then 'EXPLICIT_RETRY'
           when it.source_type = 'REPARSE'
               and it.dedupe_strategy is null then 'REPARSE'
           when it.source_type = 'REEMBED'
               and it.dedupe_strategy is null then 'REEMBED'
           else 'INITIAL'
       end,
       case
           when iti.status in ('PENDING', 'RUNNING') then 'ACTIVE'
           when iti.status = 'SUCCESS' then 'SUCCEEDED'
           else 'FAILED'
       end,
       case
           when iti.status in ('PENDING', 'RUNNING') then iti.execution_stage
           when iti.status = 'SUCCESS' then 'INDEX'
           when iti.stage in ('INDEX', 'ASKABLE') then 'INDEX'
           when iti.stage = 'EMBED' then 'EMBED'
           when iti.stage = 'CHUNK' then 'PARSE_PERSIST'
           when iti.docling_job_id is not null then 'PARSE_WAIT'
           else 'PARSE_SUBMIT'
       end,
       ipa.id,
       case
           when iti.status in ('PENDING', 'RUNNING') then greatest(iti.stage_attempt, 0)
           else 0
       end,
       greatest(iti.stage_retry_count, 0),
       case when iti.status in ('PENDING', 'RUNNING') then iti.stage_started_at else null end,
       case when iti.status in ('PENDING', 'RUNNING') then iti.next_action_at else null end,
       case
           when iti.status in ('PENDING', 'RUNNING')
               and iti.lease_token is not null
               and iti.lease_until is not null then iti.lease_token
           else null
       end,
       case
           when iti.status in ('PENDING', 'RUNNING')
               and iti.lease_token is not null
               and iti.lease_until is not null then iti.lease_until
           else null
       end,
       case when iti.status = 'FAILED' then iti.error_code else null end,
       case when iti.status = 'FAILED' then iti.error_message else null end,
       iti.created_at,
       iti.updated_at,
       case
           when iti.status in ('SUCCESS', 'FAILED')
               then coalesce(iti.finished_at, iti.updated_at)
           else null
       end
from ingestion_task_item iti
inner join ingestion_task it on it.id = iti.task_id
inner join ingestion_item_parse_attempt ipa on ipa.item_id = iti.id
    and ipa.attempt_no = greatest(iti.parse_attempt, 1);

insert into ingestion_item_artifact (
    execution_id, artifact_type, artifact_version, provenance, producer_claim_version,
    object_key, content_sha256, created_at
)
select ie.id, 'PARSE_RESULT', 1, 'LEGACY_BACKFILL', null,
       iti.parse_result_object_key, null, iti.updated_at
from ingestion_task_item iti
inner join ingestion_item_execution ie on ie.item_id = iti.id
    and ie.execution_epoch = greatest(iti.execution_epoch, 1)
where iti.parse_result_object_key is not null
union all
select ie.id, 'EMBEDDING_RESULT', 1, 'LEGACY_BACKFILL', null,
       iti.embedding_result_object_key, null, iti.updated_at
from ingestion_task_item iti
inner join ingestion_item_execution ie on ie.item_id = iti.id
    and ie.execution_epoch = greatest(iti.execution_epoch, 1)
where iti.embedding_result_object_key is not null;

update ingestion_task_item iti
inner join ingestion_item_execution ie on ie.item_id = iti.id
    and ie.execution_epoch = greatest(iti.execution_epoch, 1)
set iti.current_execution_id = ie.id;

-- A source deployment must stop every legacy ingestion writer before V11.
-- This final reconciliation turns any write that slipped through that
-- operational barrier into a failed migration instead of an invisible,
-- permanently unclaimable item.
create temporary table tmp_ingestion_v16_reconciliation_guard (
    id tinyint primary key,
    violation_count bigint not null,
    constraint chk_tmp_ingestion_v16_reconciliation_clean
        check (violation_count = 0)
);

insert into tmp_ingestion_v16_reconciliation_guard (id, violation_count)
select 1,
       -- Re-run parent ownership and task-level dedupe checks after the
       -- backfill. A legacy writer may otherwise change one of these columns
       -- after the first guard but before this migration commits.
       (select count(*)
        from ingestion_task_item iti
        left join ingestion_task it on it.id = iti.task_id
        where it.id is null
           or iti.kb_id <> it.kb_id
           or not (iti.dedupe_strategy <=> it.dedupe_strategy))
       +
       (select count(*)
        from ingestion_task it
        left join (
            select task_id, max(dedupe_strategy) as dedupe_strategy
            from ingestion_task_item
            group by task_id
        ) item_strategy on item_strategy.task_id = it.id
        where not (it.dedupe_strategy <=> item_strategy.dedupe_strategy))
       +
       -- Every eligible item must have exactly one parse attempt, and every
       -- parse-attempt fact must equal the compatibility row from which it was
       -- backfilled. This is deliberately a field-by-field comparison: mere
       -- row existence would not catch a late legacy status/job/snapshot write.
       (select count(*)
        from ingestion_task_item iti
        inner join ingestion_task it on it.id = iti.task_id
        left join ingestion_item_parse_attempt ipa
            on ipa.item_id = iti.id
           and ipa.attempt_no = greatest(iti.parse_attempt, 1)
        where (
                iti.status in ('PENDING', 'RUNNING', 'SUCCESS')
                or (
                    iti.status = 'FAILED'
                    and (
                        iti.asset_id is not null
                        or iti.docling_request_id is not null
                        or iti.parse_result_object_key is not null
                        or iti.embedding_result_object_key is not null
                    )
                )
              )
          and (
              ipa.id is null
              or ipa.id <> iti.id
              or ipa.item_id <> iti.id
              or ipa.attempt_no <> greatest(iti.parse_attempt, 1)
              or ipa.status <> case
                  when iti.parse_result_object_key is not null
                      or iti.status = 'SUCCESS'
                      or iti.execution_stage in ('EMBED', 'INDEX', 'COMPLETE')
                      or iti.stage in ('EMBED', 'INDEX', 'ASKABLE')
                      then 'SUCCEEDED'
                  when iti.status = 'FAILED' then 'FAILED'
                  else 'ACTIVE'
              end
              or not (ipa.request_id <=> iti.docling_request_id)
              or not (ipa.job_id <=> iti.docling_job_id)
              or not (ipa.source_revision <=> iti.source_revision)
              or not (ipa.request_snapshot <=> iti.parse_request_snapshot)
              or not (ipa.created_at <=> iti.created_at)
              or not (ipa.updated_at <=> iti.updated_at)
              or not (ipa.finished_at <=> case
                  when iti.status = 'SUCCESS'
                      or iti.parse_result_object_key is not null
                      or iti.execution_stage in ('EMBED', 'INDEX', 'COMPLETE')
                      or iti.stage in ('EMBED', 'INDEX', 'ASKABLE')
                      then iti.updated_at
                  when iti.status = 'FAILED'
                      then coalesce(iti.finished_at, iti.updated_at)
                  else null
              end)
              or (
                  select count(*)
                  from ingestion_item_parse_attempt all_attempts
                  where all_attempts.item_id = iti.id
              ) <> 1
          ))
       +
       -- The current execution is likewise an exact normalized projection of
       -- the eligible legacy row. Comparing all scheduling and terminal facts
       -- prevents ACTIVE/FAILED, phase, lease, fence, and error drift.
       (select count(*)
        from ingestion_task_item iti
        inner join ingestion_task it on it.id = iti.task_id
        left join ingestion_item_execution ie
            on ie.item_id = iti.id
           and ie.execution_epoch = greatest(iti.execution_epoch, 1)
        where (
                iti.status in ('PENDING', 'RUNNING', 'SUCCESS')
                or (
                    iti.status = 'FAILED'
                    and (
                        iti.asset_id is not null
                        or iti.docling_request_id is not null
                        or iti.parse_result_object_key is not null
                        or iti.embedding_result_object_key is not null
                    )
                )
              )
          and (
              ie.id is null
              or ie.id <> iti.id
              or ie.item_id <> iti.id
              or ie.execution_epoch <> greatest(iti.execution_epoch, 1)
              or ie.execution_kind <> case
                  when iti.execution_epoch > 1 then 'EXPLICIT_RETRY'
                  when it.source_type = 'REPARSE'
                      and it.dedupe_strategy is null then 'REPARSE'
                  when it.source_type = 'REEMBED'
                      and it.dedupe_strategy is null then 'REEMBED'
                  else 'INITIAL'
              end
              or ie.execution_status <> case
                  when iti.status in ('PENDING', 'RUNNING') then 'ACTIVE'
                  when iti.status = 'SUCCESS' then 'SUCCEEDED'
                  else 'FAILED'
              end
              or ie.phase <> case
                  when iti.status in ('PENDING', 'RUNNING') then iti.execution_stage
                  when iti.status = 'SUCCESS' then 'INDEX'
                  when iti.stage in ('INDEX', 'ASKABLE') then 'INDEX'
                  when iti.stage = 'EMBED' then 'EMBED'
                  when iti.stage = 'CHUNK' then 'PARSE_PERSIST'
                  when iti.docling_job_id is not null then 'PARSE_WAIT'
                  else 'PARSE_SUBMIT'
              end
              or not (ie.parse_attempt_id <=> iti.id)
              or ie.claim_version <> case
                  when iti.status in ('PENDING', 'RUNNING')
                      then greatest(iti.stage_attempt, 0)
                  else 0
              end
              or ie.phase_retry_count <> greatest(iti.stage_retry_count, 0)
              or not (ie.phase_started_at <=> case
                  when iti.status in ('PENDING', 'RUNNING')
                      then iti.stage_started_at
                  else null
              end)
              or not (ie.next_action_at <=> case
                  when iti.status in ('PENDING', 'RUNNING')
                      then iti.next_action_at
                  else null
              end)
              or not (ie.lease_token <=> case
                  when iti.status in ('PENDING', 'RUNNING')
                      and iti.lease_token is not null
                      and iti.lease_until is not null
                      then iti.lease_token
                  else null
              end)
              or not (ie.lease_until <=> case
                  when iti.status in ('PENDING', 'RUNNING')
                      and iti.lease_token is not null
                      and iti.lease_until is not null
                      then iti.lease_until
                  else null
              end)
              or not (ie.error_code <=> case
                  when iti.status = 'FAILED' then iti.error_code
                  else null
              end)
              or not (ie.error_message <=> case
                  when iti.status = 'FAILED' then iti.error_message
                  else null
              end)
              or not (ie.created_at <=> iti.created_at)
              or not (ie.updated_at <=> iti.updated_at)
              or not (ie.finished_at <=> case
                  when iti.status in ('SUCCESS', 'FAILED')
                      then coalesce(iti.finished_at, iti.updated_at)
                  else null
              end)
              or iti.current_execution_id is null
              or iti.current_execution_id <> ie.id
              or (
                  select count(*)
                  from ingestion_item_execution all_executions
                  where all_executions.item_id = iti.id
              ) <> 1
          ))
       +
       -- SKIPPED and request-validation failures intentionally have no
       -- execution history in the expand migration.
       (select count(*)
        from ingestion_task_item iti
        where not (
                iti.status in ('PENDING', 'RUNNING', 'SUCCESS')
                or (
                    iti.status = 'FAILED'
                    and (
                        iti.asset_id is not null
                        or iti.docling_request_id is not null
                        or iti.parse_result_object_key is not null
                        or iti.embedding_result_object_key is not null
                    )
                )
              )
          and (
              iti.current_execution_id is not null
              or exists (
                  select 1
                  from ingestion_item_parse_attempt ipa
                  where ipa.item_id = iti.id
              )
              or exists (
                  select 1
                  from ingestion_item_execution ie
                  where ie.item_id = iti.id
              )
          ))
       +
       -- Artifact reconciliation is bidirectional. Each old pointer requires
       -- one exact LEGACY_BACKFILL row, and the registry may not contain a row
       -- for an artifact pointer that is absent from the compatibility item.
       (select count(*)
        from ingestion_task_item iti
        inner join ingestion_item_execution ie
            on ie.id = iti.current_execution_id
           and ie.item_id = iti.id
        where (
            select count(*)
            from ingestion_item_artifact all_artifacts
            where all_artifacts.execution_id = ie.id
        ) <> (
            case when iti.parse_result_object_key is null then 0 else 1 end
            + case when iti.embedding_result_object_key is null then 0 else 1 end
        ))
       +
       (select count(*)
        from ingestion_task_item iti
        inner join ingestion_item_execution ie
            on ie.id = iti.current_execution_id
           and ie.item_id = iti.id
        left join ingestion_item_artifact iia
            on iia.execution_id = ie.id
           and iia.artifact_type = 'PARSE_RESULT'
        where iti.parse_result_object_key is not null
          and (
              iia.execution_id is null
              or iia.artifact_version <> 1
              or iia.provenance <> 'LEGACY_BACKFILL'
              or iia.producer_claim_version is not null
              or iia.object_key <> iti.parse_result_object_key
              or iia.content_sha256 is not null
              or not (iia.created_at <=> iti.updated_at)
          ))
       +
       (select count(*)
        from ingestion_task_item iti
        inner join ingestion_item_execution ie
            on ie.id = iti.current_execution_id
           and ie.item_id = iti.id
        left join ingestion_item_artifact iia
            on iia.execution_id = ie.id
           and iia.artifact_type = 'EMBEDDING_RESULT'
        where iti.embedding_result_object_key is not null
          and (
              iia.execution_id is null
              or iia.artifact_version <> 1
              or iia.provenance <> 'LEGACY_BACKFILL'
              or iia.producer_claim_version is not null
              or iia.object_key <> iti.embedding_result_object_key
              or iia.content_sha256 is not null
              or not (iia.created_at <=> iti.updated_at)
          ));

drop temporary table tmp_ingestion_v16_reconciliation_guard;
