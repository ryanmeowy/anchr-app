alter table asset
  add column version_group_id varchar(64) null after file_hash,
  add column version_no int not null default 1 after version_group_id,
  add column previous_asset_id varchar(64) null after version_no;

create index idx_doc_version_group on asset(kb_id, version_group_id, version_no);

alter table ingestion_task_item
  add column dedupe_strategy varchar(32) null after progress,
  add column duplicate_asset_id varchar(64) null after dedupe_result;
