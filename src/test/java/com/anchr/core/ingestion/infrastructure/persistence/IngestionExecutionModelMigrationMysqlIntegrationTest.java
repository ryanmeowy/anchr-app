package com.anchr.core.ingestion.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class IngestionExecutionModelMigrationMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_ingestion_model_bootstrap")
            .withUsername("anchr")
            .withPassword("anchr");

    @Test
    void migration_shouldBackfillNormalizedExecutionModelWithoutDroppingCompatibilityColumns() {
        DataSource dataSource = resetDatabase();
        migrateToV10(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "1000", "UPLOAD");
        insertTask(jdbc, "2000", "REEMBED");
        insertTask(jdbc, "3000", "REEMBED");
        insertTask(jdbc, "4000", "REPARSE");

        insertItem(jdbc, ItemSeed.builder()
                .id("1001")
                .taskId("1000")
                .assetId("asset-1001")
                .stage("PARSE")
                .status("PENDING")
                .progress(20)
                .dedupeStrategy("SKIP")
                .parseAttempt(3)
                .requestId("1000:1001:3")
                .jobId("job-1001")
                .sourceRevision("v1:" + "a".repeat(64))
                .executionStage("PARSE_WAIT")
                .executionEpoch(1)
                .claimVersion(5)
                .stageRetryCount(2)
                .activeLease(true)
                .requestSnapshot("""
                        {"artifactVersion":1,"contractVersion":2,"fileName":"active.pdf"}
                        """.trim())
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("1002")
                .taskId("1000")
                .assetId("asset-1002")
                .stage("ASKABLE")
                .status("SUCCESS")
                .progress(100)
                .dedupeStrategy("SKIP")
                .parseAttempt(1)
                .requestId("1000:1002:1")
                .jobId("job-1002")
                .sourceRevision("v1:" + "b".repeat(64))
                .executionStage("COMPLETE")
                .executionEpoch(1)
                .claimVersion(0)
                .parseObjectKey("ingestion/1000/1002/parse-result.v1.json.gz")
                .embeddingObjectKey("ingestion/1000/1002/embedding-result.v1.json.gz")
                .terminal(true)
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("1003")
                .taskId("1000")
                .assetId("asset-1003")
                .stage("ASKABLE")
                .status("SKIPPED")
                .progress(100)
                .dedupeStrategy("SKIP")
                .executionStage("COMPLETE")
                .executionEpoch(1)
                .terminal(true)
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("1004")
                .taskId("1000")
                .stage("UPLOAD")
                .status("FAILED")
                .progress(0)
                .dedupeStrategy("SKIP")
                .executionStage("FAILED")
                .executionEpoch(1)
                .errorCode("UNSUPPORTED_FILE_TYPE")
                .errorMessage("unsupported")
                .terminal(true)
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("1005")
                .taskId("1000")
                .assetId("asset-1005")
                .stage("EMBED")
                .status("FAILED")
                .progress(55)
                .dedupeStrategy("SKIP")
                .parseAttempt(2)
                .requestId("1000:1005:2")
                .jobId("job-1005")
                .sourceRevision("v1:" + "c".repeat(64))
                .executionStage("FAILED")
                .executionEpoch(2)
                .claimVersion(0)
                .stageRetryCount(3)
                .parseObjectKey("ingestion/1000/1005/parse-result.v1.json.gz")
                .errorCode("EMBEDDING_RESULT_EMPTY")
                .errorMessage("embedding failed")
                .terminal(true)
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("1006")
                .taskId("1000")
                .assetId("asset-1006")
                .stage("PARSE")
                .status("FAILED")
                .progress(20)
                .dedupeStrategy("SKIP")
                .parseAttempt(1)
                .requestId("1000:1006:1")
                .jobId("job-1006")
                .sourceRevision("v1:" + "e".repeat(64))
                .executionStage("FAILED")
                .executionEpoch(1)
                .errorCode("TEXT_PARSE_FAILED")
                .errorMessage("parse failed")
                .terminal(true)
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("2001")
                .taskId("2000")
                .assetId("asset-2001")
                .stage("EMBED")
                .status("PENDING")
                .progress(60)
                .parseAttempt(1)
                .requestId("2000:2001:1")
                .sourceRevision("v1:" + "d".repeat(64))
                .executionStage("PARSE_SUBMIT")
                .executionEpoch(1)
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("3001")
                .taskId("3000")
                .assetId("asset-3001")
                .stage("UPLOAD")
                .status("PENDING")
                .progress(0)
                .dedupeStrategy("SKIP")
                .parseAttempt(1)
                .requestId("3000:3001:1")
                .sourceRevision("v1:" + "f".repeat(64))
                .executionStage("PARSE_SUBMIT")
                .executionEpoch(1)
                .build());
        insertItem(jdbc, ItemSeed.builder()
                .id("4001")
                .taskId("4000")
                .assetId("asset-4001")
                .stage("UPLOAD")
                .status("PENDING")
                .progress(0)
                .dedupeStrategy("SKIP")
                .parseAttempt(1)
                .requestId("4000:4001:1")
                .sourceRevision("v1:" + "0".repeat(64))
                .executionStage("PARSE_SUBMIT")
                .executionEpoch(1)
                .build());

        migrateAll(dataSource);

        assertThat(jdbc.queryForObject(
                "select dedupe_strategy from ingestion_task where id = 1000", String.class))
                .isEqualTo("SKIP");
        assertThat(jdbc.queryForObject(
                "select dedupe_strategy from ingestion_task where id = 2000", String.class))
                .isNull();

        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_parse_attempt", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_execution", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_artifact", Integer.class))
                .isEqualTo(3);

        Map<String, Object> active = row(jdbc, "1001");
        assertThat(active)
                .containsEntry("execution_kind", "INITIAL")
                .containsEntry("execution_status", "ACTIVE")
                .containsEntry("phase", "PARSE_WAIT");
        assertThat(((Number) active.get("claim_version")).longValue()).isEqualTo(5L);
        assertThat(((Number) active.get("phase_retry_count")).intValue()).isEqualTo(2);
        assertThat(active.get("lease_token")).isEqualTo("lease-1001");
        assertThat(active.get("finished_at")).isNull();
        Map<String, Object> activeAttempt = jdbc.queryForMap("""
                select status, request_snapshot
                from ingestion_item_parse_attempt
                where item_id = 1001 and attempt_no = 3
                """);
        assertThat(activeAttempt.get("status")).isEqualTo("ACTIVE");
        assertThat(activeAttempt.get("request_snapshot").toString()).contains("\"active.pdf\"");

        Map<String, Object> succeeded = row(jdbc, "1002");
        assertThat(succeeded)
                .containsEntry("execution_kind", "INITIAL")
                .containsEntry("execution_status", "SUCCEEDED")
                .containsEntry("phase", "INDEX");
        assertThat(succeeded.get("phase_started_at")).isNull();
        assertThat(succeeded.get("finished_at")).isNotNull();
        assertThat(jdbc.queryForObject("""
                select status
                from ingestion_item_parse_attempt
                where item_id = 1002
                """, String.class)).isEqualTo("SUCCEEDED");

        Map<String, Object> failedRetry = row(jdbc, "1005");
        assertThat(failedRetry)
                .containsEntry("execution_kind", "EXPLICIT_RETRY")
                .containsEntry("execution_status", "FAILED")
                .containsEntry("phase", "EMBED")
                .containsEntry("error_code", "EMBEDDING_RESULT_EMPTY")
                .containsEntry("error_message", "embedding failed");
        assertThat(failedRetry.get("phase_started_at")).isNull();
        assertThat(jdbc.queryForObject("""
                select status
                from ingestion_item_parse_attempt
                where item_id = 1005
                """, String.class)).isEqualTo("SUCCEEDED");
        assertThat(row(jdbc, "1006"))
                .containsEntry("execution_status", "FAILED")
                .containsEntry("phase", "PARSE_WAIT")
                .containsEntry("error_code", "TEXT_PARSE_FAILED");
        assertThat(jdbc.queryForObject("""
                select status
                from ingestion_item_parse_attempt
                where item_id = 1006
                """, String.class)).isEqualTo("FAILED");

        assertThat(row(jdbc, "2001"))
                .containsEntry("execution_kind", "REEMBED")
                .containsEntry("execution_status", "ACTIVE")
                .containsEntry("phase", "PARSE_SUBMIT");
        assertThat(row(jdbc, "3001"))
                .containsEntry("execution_kind", "INITIAL")
                .containsEntry("execution_status", "ACTIVE")
                .containsEntry("phase", "PARSE_SUBMIT");
        assertThat(row(jdbc, "4001"))
                .containsEntry("execution_kind", "INITIAL")
                .containsEntry("execution_status", "ACTIVE")
                .containsEntry("phase", "PARSE_SUBMIT");

        assertThat(jdbc.queryForObject(
                "select current_execution_id from ingestion_task_item where id = 1001", Long.class))
                .isEqualTo(1001L);
        assertThat(jdbc.queryForObject(
                "select current_execution_id from ingestion_task_item where id = 1003", Long.class))
                .isNull();
        assertThat(jdbc.queryForObject(
                "select current_execution_id from ingestion_task_item where id = 1004", Long.class))
                .isNull();

        List<Map<String, Object>> artifacts = jdbc.queryForList("""
                select execution_id, artifact_type, provenance,
                       producer_claim_version, content_sha256
                from ingestion_item_artifact
                order by execution_id, artifact_type
                """);
        assertThat(artifacts).allSatisfy(artifact -> {
            assertThat(artifact.get("provenance")).isEqualTo("LEGACY_BACKFILL");
            assertThat(artifact.get("producer_claim_version")).isNull();
            assertThat(artifact.get("content_sha256")).isNull();
        });

        assertThat(columnNames(jdbc, "ingestion_task_item"))
                .contains(
                        "kb_id", "dedupe_strategy", "stage", "progress",
                        "execution_stage", "execution_epoch", "stage_attempt",
                        "parse_request_snapshot", "parse_result_object_key",
                        "embedding_result_object_key", "current_execution_id");
        assertThat(columnExtra(jdbc, "ingestion_item_parse_attempt", "id"))
                .isEqualTo("auto_increment");
        assertThat(columnExtra(jdbc, "ingestion_item_execution", "id"))
                .isEqualTo("auto_increment");
        assertThat(jdbc.queryForObject("""
                select count(*)
                from information_schema.referential_constraints
                where constraint_schema = database()
                  and table_name = 'ingestion_task_item'
                  and constraint_name = 'fk_ingestion_item_current_execution'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void migration_shouldRejectMixedTaskDedupeStrategyBeforePersistentDdl() {
        DataSource dataSource = resetDatabase();
        migrateToV10(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "3000", "UPLOAD");
        insertItem(jdbc, ItemSeed.builder()
                .id("3001").taskId("3000").stage("UPLOAD").status("FAILED")
                .dedupeStrategy("SKIP").executionStage("FAILED").terminal(true).build());
        insertItem(jdbc, ItemSeed.builder()
                .id("3002").taskId("3000").stage("UPLOAD").status("FAILED")
                .dedupeStrategy("OVERWRITE").executionStage("FAILED").terminal(true).build());

        assertThatThrownBy(() -> migrateAll(dataSource))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V11__add_ingestion_task_dedupe_strategy.sql");
        assertThat(columnNames(jdbc, "ingestion_task")).doesNotContain("dedupe_strategy");
    }

    @Test
    void migration_shouldRejectParentKbMismatchBeforePersistentDdl() {
        DataSource dataSource = resetDatabase();
        migrateToV10(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "4000", "UPLOAD");
        insertItem(jdbc, ItemSeed.builder()
                .id("4001").taskId("4000").kbId("2").stage("UPLOAD").status("FAILED")
                .dedupeStrategy("SKIP").executionStage("FAILED").terminal(true).build());

        assertThatThrownBy(() -> migrateAll(dataSource))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V11__add_ingestion_task_dedupe_strategy.sql");
        assertThat(columnNames(jdbc, "ingestion_task")).doesNotContain("dedupe_strategy");
    }

    @Test
    void migration_shouldRejectSuccessPairedWithFailedExecutionStage() {
        DataSource dataSource = resetDatabase();
        migrateToV10(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "4500", "UPLOAD");
        insertItem(jdbc, ItemSeed.builder()
                .id("4501").taskId("4500").assetId("asset-4501")
                .stage("ASKABLE").status("SUCCESS")
                .dedupeStrategy("SKIP").executionStage("FAILED").terminal(true).build());

        assertThatThrownBy(() -> migrateAll(dataSource))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V11__add_ingestion_task_dedupe_strategy.sql");
        assertThat(columnNames(jdbc, "ingestion_task")).doesNotContain("dedupe_strategy");
    }

    @Test
    void migration_shouldRejectFailurePairedWithCompleteExecutionStage() {
        DataSource dataSource = resetDatabase();
        migrateToV10(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "4600", "UPLOAD");
        insertItem(jdbc, ItemSeed.builder()
                .id("4601").taskId("4600").assetId("asset-4601")
                .stage("PARSE").status("FAILED")
                .dedupeStrategy("SKIP").executionStage("COMPLETE").terminal(true).build());

        assertThatThrownBy(() -> migrateAll(dataSource))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V11__add_ingestion_task_dedupe_strategy.sql");
        assertThat(columnNames(jdbc, "ingestion_task")).doesNotContain("dedupe_strategy");
    }

    @Test
    void migration_shouldRollbackBackfillWhenFinalReconciliationDetectsLateWrite() {
        DataSource dataSource = resetDatabase();
        migrateToV10(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "4700", "UPLOAD");
        insertItem(jdbc, ItemSeed.builder()
                .id("4701").taskId("4700").assetId("asset-4701")
                .stage("PARSE").status("RUNNING").progress(20)
                .dedupeStrategy("SKIP").executionStage("PARSE_WAIT")
                .requestId("4700:4701:1").jobId("job-4701").claimVersion(2)
                .build());
        migrateTo(dataSource, "15");
        jdbc.execute("""
                create trigger trg_ingestion_v16_late_write
                before update on ingestion_task_item
                for each row
                set new.stage_attempt = case
                    when old.current_execution_id is null
                     and new.current_execution_id is not null
                        then old.stage_attempt + 1
                    else new.stage_attempt
                end
                """);

        assertThatThrownBy(() -> migrateAll(dataSource))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V16__backfill_ingestion_execution_model.sql");

        assertThat(jdbc.queryForObject(
                "select current_execution_id from ingestion_task_item where id = 4701",
                Long.class)).isNull();
        assertThat(jdbc.queryForObject(
                "select stage_attempt from ingestion_task_item where id = 4701",
                Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_parse_attempt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_execution", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_artifact", Integer.class)).isZero();
    }

    @Test
    void schema_shouldRejectCrossItemParseAttemptAndInvalidArtifactDigest() {
        DataSource dataSource = resetDatabase();
        migrateToV10(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, "5000", "UPLOAD");
        insertItem(jdbc, ItemSeed.builder()
                .id("5001").taskId("5000").assetId("asset-5001")
                .stage("UPLOAD").status("PENDING").dedupeStrategy("SKIP")
                .requestId("5000:5001:1").executionStage("PARSE_SUBMIT").build());
        insertItem(jdbc, ItemSeed.builder()
                .id("5002").taskId("5000").assetId("asset-5002")
                .stage("UPLOAD").status("PENDING").dedupeStrategy("SKIP")
                .requestId("5000:5002:1").executionStage("PARSE_SUBMIT").build());
        migrateAll(dataSource);

        assertThatThrownBy(() -> jdbc.update("""
                update ingestion_item_execution
                set parse_attempt_id = 5002
                where id = 5001
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update ingestion_task_item
                set current_execution_id = 5002
                where id = 5001
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update ingestion_task_item
                set progress = 101
                where id = 5001
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into ingestion_item_artifact (
                    execution_id, artifact_type, artifact_version, provenance,
                    producer_claim_version, object_key, content_sha256, created_at
                ) values (
                    5001, 'PARSE_RESULT', 1, 'PRODUCED', 1,
                    'ingestion/5001/parse-result.json.gz', 'not-a-sha', current_timestamp(6)
                )
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into ingestion_item_artifact (
                    execution_id, artifact_type, artifact_version, provenance,
                    producer_claim_version, object_key, content_sha256, created_at
                ) values (
                    5001, 'PARSE_RESULT', 1, 'PRODUCED', 1,
                    'ingestion/5001/parse-result.json.gz', null, current_timestamp(6)
                )
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into ingestion_item_artifact (
                    execution_id, artifact_type, artifact_version, provenance,
                    producer_claim_version, object_key, content_sha256, created_at
                ) values (
                    5001, 'UNKNOWN_RESULT', 1, 'PRODUCED', 1,
                    'ingestion/5001/unknown-result.json.gz', ?, current_timestamp(6)
                )
                """, "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private DataSource resetDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        return dataSource;
    }

    private void migrateToV10(DataSource dataSource) {
        migrateTo(dataSource, "10");
    }

    private void migrateTo(DataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void migrateAll(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void insertTask(JdbcTemplate jdbc, String id, String sourceType) {
        jdbc.update("""
                insert into ingestion_task (
                    id, kb_id, source_type, status, total_count, success_count,
                    failure_count, running_count, created_by, updated_by,
                    created_at, updated_at
                ) values (
                    ?, 1, ?, 'PENDING', 1, 0, 0, 0, 'migration-user', 'migration-user',
                    '2026-01-01 00:00:00', '2026-01-01 00:00:00'
                )
                """, id, sourceType);
    }

    private void insertItem(JdbcTemplate jdbc, ItemSeed seed) {
        jdbc.update("""
                insert into ingestion_task_item (
                    id, task_id, kb_id, asset_id, file_name, file_hash, source_url,
                    parse_attempt, docling_request_id, docling_job_id, source_revision,
                    execution_stage, execution_epoch, stage_attempt, stage_retry_count,
                    stage_started_at, next_action_at, lease_token, lease_until,
                    parse_request_snapshot, parse_result_object_key, embedding_result_object_key,
                    stage, status, progress, dedupe_strategy, dedupe_result,
                    error_code, error_message, created_at, updated_at, finished_at
                ) values (
                    ?, ?, ?, ?, ?, 'hash', null,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    cast(? as json), ?, ?,
                    ?, ?, ?, ?, 'NEW',
                    ?, ?, '2026-01-01 00:00:00', '2026-01-01 00:01:00', ?
                )
                """,
                seed.id,
                seed.taskId,
                seed.kbId,
                seed.assetId,
                seed.id + ".pdf",
                seed.parseAttempt,
                seed.requestId,
                seed.jobId,
                seed.sourceRevision,
                seed.executionStage,
                seed.executionEpoch,
                seed.claimVersion,
                seed.stageRetryCount,
                seed.activeLease ? "2026-01-01 00:00:30" : null,
                seed.activeLease ? "2026-01-01 00:02:00" : null,
                seed.activeLease ? "lease-" + seed.id : null,
                seed.activeLease ? "2026-01-01 00:05:00" : null,
                seed.requestSnapshot,
                seed.parseObjectKey,
                seed.embeddingObjectKey,
                seed.stage,
                seed.status,
                seed.progress,
                seed.dedupeStrategy,
                seed.errorCode,
                seed.errorMessage,
                seed.terminal ? "2026-01-01 00:01:00" : null);
    }

    private Map<String, Object> row(JdbcTemplate jdbc, String itemId) {
        return jdbc.queryForMap("""
                select execution_kind, execution_status, phase, claim_version,
                       phase_retry_count, phase_started_at, lease_token,
                       error_code, error_message, finished_at
                from ingestion_item_execution
                where item_id = ?
                """, itemId);
    }

    private List<String> columnNames(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = database()
                  and table_name = ?
                order by ordinal_position
                """, String.class, table);
    }

    private String columnExtra(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                select extra
                from information_schema.columns
                where table_schema = database()
                  and table_name = ?
                  and column_name = ?
                """, String.class, table, column);
    }

    @lombok.Builder
    private static class ItemSeed {
        private String id;
        private String taskId;
        @lombok.Builder.Default
        private String kbId = "1";
        private String assetId;
        @lombok.Builder.Default
        private String stage = "UPLOAD";
        @lombok.Builder.Default
        private String status = "PENDING";
        private int progress;
        private String dedupeStrategy;
        @lombok.Builder.Default
        private int parseAttempt = 1;
        private String requestId;
        private String jobId;
        private String sourceRevision;
        @lombok.Builder.Default
        private String executionStage = "PARSE_SUBMIT";
        @lombok.Builder.Default
        private long executionEpoch = 1;
        private int claimVersion;
        private int stageRetryCount;
        private boolean activeLease;
        private String requestSnapshot;
        private String parseObjectKey;
        private String embeddingObjectKey;
        private String errorCode;
        private String errorMessage;
        private boolean terminal;
    }
}
