create table if not exists outbox_event (
    id bigint primary key auto_increment,
    event_type varchar(64) not null,
    aggregate_type varchar(64) not null,
    aggregate_id bigint not null,
    payload json not null,
    status varchar(20) not null default 'PENDING',
    retry_count int not null default 0,
    next_retry_at datetime null,
    lock_token varchar(64) null,
    locked_at datetime null,
    processed_at datetime null,
    last_error text null,
    created_by varchar(64) not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    index idx_outbox_poll (status, next_retry_at, id),
    index idx_outbox_locked (status, locked_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
