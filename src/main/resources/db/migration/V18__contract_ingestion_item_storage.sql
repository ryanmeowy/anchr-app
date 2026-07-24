-- Some development databases briefly received the pre-106B CHECK/FK design
-- before those constraints were removed from the migration baseline. Business
-- state and ownership are enforced by the application and fenced SQL, so clear
-- any such residual constraints before dropping the compatibility columns.
set session group_concat_max_len = 8192;

set @drop_ingestion_item_constraints = (
    select if(
        count(*) = 0,
        'do 0',
        concat(
            'alter table ingestion_task_item ',
            group_concat(
                case constraint_type
                    when 'CHECK' then concat(
                        'drop check `', replace(constraint_name, '`', '``'), '`')
                    when 'FOREIGN KEY' then concat(
                        'drop foreign key `', replace(constraint_name, '`', '``'), '`')
                end
                order by constraint_type, constraint_name
                separator ', '
            )
        )
    )
    from information_schema.table_constraints
    where table_schema = database()
      and table_name = 'ingestion_task_item'
      and constraint_type in ('CHECK', 'FOREIGN KEY')
);

prepare drop_ingestion_item_constraints
    from @drop_ingestion_item_constraints;
execute drop_ingestion_item_constraints;
deallocate prepare drop_ingestion_item_constraints;

set @drop_ingestion_execution_constraints = (
    select if(
        count(*) = 0,
        'do 0',
        concat(
            'alter table ingestion_item_execution ',
            group_concat(
                case constraint_type
                    when 'CHECK' then concat(
                        'drop check `', replace(constraint_name, '`', '``'), '`')
                    when 'FOREIGN KEY' then concat(
                        'drop foreign key `', replace(constraint_name, '`', '``'), '`')
                end
                order by constraint_type, constraint_name
                separator ', '
            )
        )
    )
    from information_schema.table_constraints
    where table_schema = database()
      and table_name = 'ingestion_item_execution'
      and constraint_type in ('CHECK', 'FOREIGN KEY')
);

prepare drop_ingestion_execution_constraints
    from @drop_ingestion_execution_constraints;
execute drop_ingestion_execution_constraints;
deallocate prepare drop_ingestion_execution_constraints;

set @drop_ingestion_attempt_constraints = (
    select if(
        count(*) = 0,
        'do 0',
        concat(
            'alter table ingestion_item_parse_attempt ',
            group_concat(
                case constraint_type
                    when 'CHECK' then concat(
                        'drop check `', replace(constraint_name, '`', '``'), '`')
                    when 'FOREIGN KEY' then concat(
                        'drop foreign key `', replace(constraint_name, '`', '``'), '`')
                end
                order by constraint_type, constraint_name
                separator ', '
            )
        )
    )
    from information_schema.table_constraints
    where table_schema = database()
      and table_name = 'ingestion_item_parse_attempt'
      and constraint_type in ('CHECK', 'FOREIGN KEY')
);

prepare drop_ingestion_attempt_constraints
    from @drop_ingestion_attempt_constraints;
execute drop_ingestion_attempt_constraints;
deallocate prepare drop_ingestion_attempt_constraints;

alter table ingestion_task_item
    drop index idx_ingestion_item_claim,
    drop index idx_ingestion_task_claim,
    drop index idx_task_item_kb_status,
    drop column kb_id,
    drop column parse_attempt,
    drop column docling_request_id,
    drop column docling_job_id,
    drop column source_revision,
    drop column execution_stage,
    drop column execution_epoch,
    drop column stage_attempt,
    drop column stage_retry_count,
    drop column stage_started_at,
    drop column next_action_at,
    drop column lease_token,
    drop column lease_until,
    drop column parse_request_snapshot,
    drop column parse_result_object_key,
    drop column dedupe_strategy;

alter table ingestion_item_parse_attempt
    drop index uk_ingestion_parse_attempt_id_item;

alter table ingestion_item_execution
    drop index uk_ingestion_execution_id_item;

set @drop_legacy_embedding_pointer = (
    select if(
        exists(
            select 1
            from information_schema.columns
            where table_schema = database()
              and table_name = 'ingestion_task_item'
              and column_name = 'embedding_result_object_key'
        ),
        'alter table ingestion_task_item drop column embedding_result_object_key',
        'do 0'
    )
);

prepare drop_legacy_embedding_pointer
    from @drop_legacy_embedding_pointer;
execute drop_legacy_embedding_pointer;
deallocate prepare drop_legacy_embedding_pointer;
