create table ingestion_item_parse_attempt (
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
    constraint uk_ingestion_parse_attempt_id_item unique (id, item_id),
    constraint chk_ingestion_parse_attempt_no_positive check (attempt_no >= 1),
    constraint chk_ingestion_parse_attempt_status check (
        status in ('ACTIVE', 'SUCCEEDED', 'FAILED')
    ),
    constraint fk_ingestion_parse_attempt_item
        foreign key (item_id) references ingestion_task_item (id)
        on update restrict on delete restrict
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
