create table if not exists ingestion_item_artifact (
    execution_id bigint not null,
    artifact_type varchar(32) character set ascii collate ascii_bin not null,
    artifact_version int not null,
    provenance varchar(32) character set ascii collate ascii_bin not null,
    producer_claim_version bigint null,
    object_key varchar(1024) character set utf8mb4 collate utf8mb4_bin not null,
    content_sha256 char(64) character set ascii collate ascii_bin null,
    created_at datetime(6) not null,
    primary key (execution_id, artifact_type)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
