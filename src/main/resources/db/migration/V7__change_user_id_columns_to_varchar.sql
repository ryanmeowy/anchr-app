alter table knowledge_base
    modify column created_by varchar(64) not null default 'system',
    modify column updated_by varchar(64) not null default 'system';

alter table asset
    modify column created_by varchar(64) not null default 'system',
    modify column updated_by varchar(64) not null default 'system';

alter table ingestion_task
    modify column created_by varchar(64) not null default 'system',
    modify column updated_by varchar(64) not null default 'system';

alter table capability_config
    modify column updated_by varchar(64) not null default 'system';

alter table storage_config
    modify column updated_by varchar(64) not null default 'system';

alter table activity_event
    modify column user_id varchar(32) not null default 'system';
