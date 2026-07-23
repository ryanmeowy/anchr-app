create table if not exists ingestion_item_parse_attempt (
    id bigint auto_increment primary key,
    item_id bigint not null,
    attempt_no int not null,
    status varchar(16) character set ascii collate ascii_bin not null,
    request_id varchar(200) character set utf8mb4 collate utf8mb4_bin null,
    job_id varchar(64) character set ascii collate ascii_bin null,
    source_revision varchar(80) character set ascii collate ascii_bin null,
    request_snapshot json null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    finished_at datetime(6) null,
    constraint uk_ingestion_parse_attempt_item_no unique (item_id, attempt_no),
    constraint uk_ingestion_parse_attempt_request unique (request_id),
    constraint uk_ingestion_parse_attempt_id_item unique (id, item_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
