alter table asset
    add column active_index_generation bigint not null default 0 after indexed_segment_count;

alter table ingestion_task_item
    add column target_index_generation bigint null after asset_id,
    add index idx_ingestion_item_asset_generation (
        asset_id, target_index_generation
    );
