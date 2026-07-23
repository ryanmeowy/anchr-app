create table ingestion_item_artifact (
    execution_id bigint not null,
    artifact_type varchar(32) character set ascii collate ascii_bin not null,
    artifact_version int not null,
    provenance varchar(32) character set ascii collate ascii_bin not null,
    producer_claim_version bigint null,
    object_key varchar(1024) character set utf8mb4 collate utf8mb4_bin not null,
    content_sha256 char(64) character set ascii collate ascii_bin null,
    created_at datetime(6) not null,
    primary key (execution_id, artifact_type),
    constraint chk_ingestion_artifact_type check (
        artifact_type in ('PARSE_RESULT', 'EMBEDDING_RESULT')
    ),
    constraint chk_ingestion_artifact_version_positive check (artifact_version >= 1),
    constraint chk_ingestion_artifact_provenance check (
        provenance in ('PRODUCED', 'LEGACY_BACKFILL')
    ),
    constraint chk_ingestion_artifact_producer_positive check (
        producer_claim_version is null or producer_claim_version >= 1
    ),
    constraint chk_ingestion_artifact_sha256 check (
        content_sha256 is null or content_sha256 regexp '^[0-9a-f]{64}$'
    ),
    constraint chk_ingestion_artifact_provenance_metadata check (
        (provenance = 'PRODUCED'
            and producer_claim_version is not null
            and content_sha256 is not null)
        or
        (provenance = 'LEGACY_BACKFILL'
            and producer_claim_version is null)
    ),
    constraint fk_ingestion_artifact_execution
        foreign key (execution_id) references ingestion_item_execution (id)
        on update restrict on delete restrict
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
