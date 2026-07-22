alter table ingestion_task
  add column client_request_id varchar(128) character set utf8mb4 collate utf8mb4_bin null after source_type,
  add column request_hash varchar(80) character set ascii collate ascii_bin null after client_request_id,
  add unique key uk_ingestion_task_creator_request (created_by, client_request_id);
