package com.anchr.core.ingestion.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class IngestionItemStorageMigrationMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_ingestion_item_migration_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateFromNormalizedV15Schema() {
        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("15"))
                .load()
                .migrate();

        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                alter table ingestion_task_item
                add column embedding_result_object_key varchar(1024) null
                """);
        seedRepresentativeState();
        addResidualDatabaseConstraints();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void v18_shouldRemoveLegacyItemStateWithoutChangingNormalizedExecution() {
        List<String> itemColumns = jdbc.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'ingestion_task_item'
                order by ordinal_position
                """, String.class);
        assertThat(itemColumns).containsExactly(
                "id",
                "task_id",
                "current_execution_id",
                "asset_id",
                "file_name",
                "file_hash",
                "source_url",
                "stage",
                "status",
                "progress",
                "dedupe_result",
                "duplicate_asset_id",
                "error_code",
                "error_message",
                "created_at",
                "updated_at",
                "finished_at");

        List<String> itemIndexes = jdbc.queryForList("""
                select distinct index_name
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'ingestion_task_item'
                """, String.class);
        assertThat(itemIndexes).containsExactlyInAnyOrder(
                "PRIMARY",
                "idx_ingestion_item_current_execution",
                "idx_task_item_asset",
                "idx_task_item_task");

        assertThat(jdbc.queryForList("""
                select table_name, constraint_name, constraint_type
                from information_schema.table_constraints
                where table_schema = database()
                  and table_name in (
                    'ingestion_task_item',
                    'ingestion_item_parse_attempt',
                    'ingestion_item_execution'
                  )
                  and constraint_type in ('CHECK', 'FOREIGN KEY')
                """)).isEmpty();

        Map<String, Object> state = jdbc.queryForMap("""
                select iti.task_id, iti.current_execution_id, iti.asset_id,
                       iti.stage, iti.status, iti.progress,
                       ie.execution_epoch, ie.phase, ipa.attempt_no,
                       ipa.request_id
                from ingestion_task_item iti
                join ingestion_item_execution ie
                  on ie.id = iti.current_execution_id
                 and ie.item_id = iti.id
                join ingestion_item_parse_attempt ipa
                  on ipa.id = ie.parse_attempt_id
                 and ipa.item_id = iti.id
                where iti.id = 3001
                """);
        assertThat(state)
                .containsEntry("task_id", 1001L)
                .containsEntry("current_execution_id", 5001L)
                .containsEntry("asset_id", "asset-1")
                .containsEntry("stage", "PARSE")
                .containsEntry("status", "RUNNING")
                .containsEntry("progress", 30)
                .containsEntry("execution_epoch", 1L)
                .containsEntry("phase", "PARSE_WAIT")
                .containsEntry("attempt_no", 1)
                .containsEntry("request_id", "normalized-request");

        assertThat(jdbc.queryForMap("""
                select artifact_version, provenance, producer_claim_version,
                       object_key, content_sha256
                from ingestion_item_artifact
                where execution_id = 5001
                  and artifact_type = 'PARSE_RESULT'
                """))
                .containsEntry("artifact_version", 1)
                .containsEntry("provenance", "PRODUCED")
                .containsEntry("producer_claim_version", 2L)
                .containsEntry("object_key", "parse/normalized.json.gz")
                .containsEntry("content_sha256", "a".repeat(64));

        assertThat(jdbc.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '18' and success = 1
                """, Integer.class)).isEqualTo(1);
    }

    private static void seedRepresentativeState() {
        jdbc.update("""
                insert into ingestion_task (
                  id, kb_id, source_type, client_request_id, request_hash,
                  dedupe_strategy, status, total_count, success_count,
                  failure_count, running_count, created_by, updated_by,
                  created_at, updated_at
                ) values (
                  1001, 2001, 'UPLOAD', 'verify-106b', 'request-hash',
                  'SKIP', 'RUNNING', 1, 0,
                  0, 1, 'verify', 'verify',
                  current_timestamp, current_timestamp
                )
                """);
        jdbc.update("""
                insert into ingestion_task_item (
                  id, task_id, kb_id, asset_id, file_name, file_hash,
                  parse_attempt, execution_stage, execution_epoch,
                  stage_attempt, stage_retry_count, stage, status, progress,
                  embedding_result_object_key, created_at, updated_at
                ) values (
                  3001, 1001, 2001, 'asset-1', 'verify.pdf', 'hash-1',
                  99, 'FAILED', 99,
                  7, 8, 'PARSE', 'RUNNING', 30,
                  'legacy/embedding.json', current_timestamp, current_timestamp
                )
                """);
        jdbc.update("""
                insert into ingestion_item_parse_attempt (
                  id, item_id, attempt_no, status, request_id, job_id,
                  source_revision, request_snapshot, created_at, updated_at
                ) values (
                  4001, 3001, 1, 'ACTIVE', 'normalized-request',
                  'normalized-job', 'normalized-revision',
                  json_object('normalized', true),
                  current_timestamp, current_timestamp
                )
                """);
        jdbc.update("""
                insert into ingestion_item_execution (
                  id, item_id, execution_epoch, execution_kind,
                  execution_status, phase, parse_attempt_id, claim_version,
                  phase_retry_count, phase_started_at, next_action_at,
                  lease_token, lease_until, created_at, updated_at
                ) values (
                  5001, 3001, 1, 'INITIAL',
                  'ACTIVE', 'PARSE_WAIT', 4001, 2,
                  0, current_timestamp, current_timestamp,
                  'normalized-token', current_timestamp,
                  current_timestamp, current_timestamp
                )
                """);
        jdbc.update("""
                insert into ingestion_item_artifact (
                  execution_id, artifact_type, artifact_version, provenance,
                  producer_claim_version, object_key, content_sha256, created_at
                ) values (
                  5001, 'PARSE_RESULT', 1, 'PRODUCED',
                  2, 'parse/normalized.json.gz', ?, current_timestamp
                )
                """, "a".repeat(64));
        jdbc.update("""
                update ingestion_task_item
                set current_execution_id = 5001
                where id = 3001
                """);
    }

    private static void addResidualDatabaseConstraints() {
        jdbc.execute("""
                alter table ingestion_item_parse_attempt
                  add constraint chk_ingestion_parse_attempt_no_positive
                    check (attempt_no >= 1),
                  add constraint fk_ingestion_parse_attempt_item
                    foreign key (item_id) references ingestion_task_item(id)
                """);
        jdbc.execute("""
                alter table ingestion_item_execution
                  add constraint chk_ingestion_execution_epoch_v11_positive
                    check (execution_epoch >= 1),
                  add constraint chk_ingestion_execution_lease_pair
                    check (
                      (lease_token is null and lease_until is null)
                      or (lease_token is not null and lease_until is not null)
                    ),
                  add constraint fk_ingestion_execution_item
                    foreign key (item_id) references ingestion_task_item(id),
                  add constraint fk_ingestion_execution_parse_attempt
                    foreign key (parse_attempt_id, item_id)
                    references ingestion_item_parse_attempt(id, item_id)
                """);
        jdbc.execute("""
                alter table ingestion_task_item
                  add constraint chk_ingestion_parse_attempt_positive
                    check (parse_attempt >= 1),
                  add constraint chk_ingestion_item_public_progress
                    check (progress between 0 and 100),
                  add constraint fk_ingestion_item_current_execution
                    foreign key (current_execution_id, id)
                    references ingestion_item_execution(id, item_id)
                """);
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
