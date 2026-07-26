create table if not exists embedding_profile_deployment (
    slot_id tinyint primary key,

    desired_config_id bigint null,
    desired_capability varchar(32) null,
    desired_model_name varchar(255) null,
    desired_dimension int null,
    desired_fingerprint char(64) character set ascii collate ascii_bin null,

    serving_config_id bigint null,
    serving_capability varchar(32) null,
    serving_model_name varchar(255) null,
    serving_dimension int null,
    serving_fingerprint char(64) character set ascii collate ascii_bin null,
    serving_physical_index varchar(255) null,

    target_config_id bigint null,
    target_capability varchar(32) null,
    target_model_name varchar(255) null,
    target_dimension int null,
    target_fingerprint char(64) character set ascii collate ascii_bin null,
    target_physical_index varchar(255) null,

    deployment_status varchar(32) character set ascii collate ascii_bin not null,
    task_id char(36) character set ascii collate ascii_bin null,
    start_revision bigint not null default 0,
    applied_revision bigint not null default 0,
    rebuild_migrated bigint not null default 0,
    rebuild_total bigint not null default 0,
    rebuild_phase varchar(32) character set ascii collate ascii_bin null,
    impact_image_assets bigint not null default 0,
    impact_ocr_available_assets bigint not null default 0,
    impact_ocr_empty_assets bigint not null default 0,
    impact_text_vector_failures bigint not null default 0,
    impact_visual_loss_assets bigint not null default 0,
    impact_report_ready boolean not null default false,
    impact_confirmation_required boolean not null default false,
    impact_confirmed boolean not null default true,
    deployment_version bigint not null default 0,
    owner_token varchar(128) character set ascii collate ascii_bin null,
    lease_until datetime(6) null,
    last_error varchar(2000) null,
    updated_at datetime(6) not null,
    index idx_embedding_deployment_task (task_id),
    index idx_embedding_deployment_lease (deployment_status, lease_until)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists embedding_index_write_lease (
    lease_token char(36) character set ascii collate ascii_bin primary key,
    owner_id varchar(128) character set ascii collate ascii_bin not null,
    expires_at datetime(6) not null,
    created_at datetime(6) not null,
    index idx_embedding_write_lease_expiry (expires_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table if not exists physical_index_profile (
    physical_index varchar(255) primary key,
    config_id bigint null,
    profile_fingerprint char(64) character set ascii collate ascii_bin not null,
    capability varchar(32) not null,
    model_name varchar(255) not null,
    vector_schema_version int not null,
    dimension int not null,
    max_applied_revision bigint not null default 0,
    lifecycle_status varchar(32) character set ascii collate ascii_bin not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_physical_profile_fingerprint (profile_fingerprint),
    index idx_physical_profile_config (config_id, lifecycle_status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
