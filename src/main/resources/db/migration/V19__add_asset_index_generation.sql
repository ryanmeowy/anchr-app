alter table asset
    add column active_index_generation bigint not null default 0 after indexed_segment_count;

alter table ingestion_task_item
    add column target_index_generation bigint null after asset_id,
    add index idx_ingestion_item_asset_generation (
        asset_id, target_index_generation
    );

create table if not exists asset_index_change (
    revision bigint auto_increment primary key,
    event_id varchar(64) character set ascii collate ascii_bin not null,
    kb_id bigint not null,
    asset_id bigint not null,
    operation varchar(32) character set ascii collate ascii_bin not null,
    index_generation bigint not null,
    occurred_at datetime(6) not null,
    created_by varchar(64) not null,
    unique key uk_asset_index_change_event_id (event_id),
    index idx_asset_index_change_kb_revision (kb_id, revision)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
