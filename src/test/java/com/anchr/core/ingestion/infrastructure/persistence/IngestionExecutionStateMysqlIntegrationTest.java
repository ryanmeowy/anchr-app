package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.application.impl.IngestionStageTransactionCoordinator;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class IngestionExecutionStateMysqlIntegrationTest {

    private static final String BACKFILL_TASK_ID = "9100";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_ingestion_execution_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private IngestionTaskMapper mapper;
    private IngestionTaskRepositoryImpl repository;

    @BeforeAll
    static void migrateWithActiveAndTerminalRows() {
        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertTask(jdbc, BACKFILL_TASK_ID, "backfill-user");
        insertPreV10Item(jdbc, "9101", "PARSE", "PENDING", 20);
        insertPreV10Item(jdbc, "9102", "INDEX", "RUNNING", 75);
        insertPreV10Item(jdbc, "9103", "ASKABLE", "SUCCESS", 100);
        insertPreV10Item(jdbc, "9104", "PARSE", "FAILED", 20);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from ingestion_task_item where task_id <> ?", BACKFILL_TASK_ID);
        jdbc.update("delete from ingestion_task where id <> ?", BACKFILL_TASK_ID);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new ClassPathResource("mapper/ingestion/IngestionTaskMapper.xml"));
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sessionFactory);
        mapper = sessionTemplate.getMapper(IngestionTaskMapper.class);
        repository = new IngestionTaskRepositoryImpl(mapper);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @Test
    void migration_shouldBackfillSchedulerStateAndInstallClaimIndexes() {
        assertThat(jdbc.queryForList("""
                select id, execution_stage, execution_epoch, stage_attempt, stage_retry_count,
                       stage, progress,
                       next_action_at, lease_token, lease_until
                from ingestion_task_item
                where task_id = ?
                order by id
                """, BACKFILL_TASK_ID))
                .satisfiesExactly(
                        row -> assertBackfill(row, "9101", "PARSE_SUBMIT", true),
                        row -> assertBackfill(row, "9102", "PARSE_SUBMIT", true),
                        row -> assertBackfill(row, "9103", "COMPLETE", false),
                        row -> assertBackfill(row, "9104", "FAILED", false));

        assertThat(indexColumns("idx_ingestion_item_claim"))
                .containsExactly("status", "next_action_at", "lease_until", "id");
        assertThat(indexColumns("idx_ingestion_task_claim"))
                .containsExactly("task_id", "status", "next_action_at", "lease_until", "id");
    }

    @Test
    void claimAndTransition_shouldFenceStaleWorkerAndUseDatabaseLease() {
        insertTask(jdbc, "9200", "worker-user");
        jdbc.update("""
                insert into ingestion_task_item (
                    id, task_id, kb_id, asset_id, file_name, file_hash,
                    parse_attempt, docling_request_id, source_revision,
                    execution_stage, execution_epoch, stage_attempt, stage_retry_count,
                    next_action_at, stage, status, progress, created_at, updated_at
                ) values (
                    9201, 9200, 1, 9301, 'sample.pdf', 'hash-a',
                    1, '9200:9201:1', ?, 'PARSE_SUBMIT', 1, 0, 0,
                    current_timestamp(6), 'UPLOAD', 'PENDING', 0,
                    current_timestamp(6), current_timestamp(6)
                )
                """, "v1:" + "a".repeat(64));

        assertThat(repository.listClaimableItemIds(10)).contains("9201");
        assertThat(repository.listClaimableItemIds("9200", 10)).containsExactly("9201");

        IngestionTaskItem firstClaim = transaction.execute(status ->
                repository.claimOne("9201", 60).orElseThrow());
        assertThat(firstClaim).isNotNull();
        assertThat(firstClaim.getTaskCreatedBy()).isEqualTo("worker-user");
        assertThat(firstClaim.getStatus()).isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(firstClaim.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(firstClaim.getProgress()).isEqualTo(20);
        assertThat(firstClaim.getStageAttempt()).isEqualTo(1);
        assertThat(firstClaim.getStageStartedAt()).isNotNull();
        assertThat(firstClaim.getLeaseToken()).isNotBlank();
        assertThat(firstClaim.getLeaseUntil()).isAfter(firstClaim.getStageStartedAt());
        assertThat(jdbc.queryForObject(
                "select status from ingestion_task where id = 9200", String.class)).isEqualTo("RUNNING");

        String requestSnapshot = """
                {"contractVersion":2,"fileName":"sample.pdf","options":{"chunk":true}}
                """.trim();
        assertThat(repository.updateClaimContext(IngestionClaimContext.builder()
                .itemId(firstClaim.getId())
                .executionEpoch(firstClaim.getExecutionEpoch())
                .expectedExecutionStage(firstClaim.getExecutionStage())
                .stageAttempt(firstClaim.getStageAttempt())
                .leaseToken(firstClaim.getLeaseToken())
                .parseAttempt(firstClaim.getParseAttempt())
                .doclingRequestId(firstClaim.getDoclingRequestId())
                .doclingJobId("job-1")
                .sourceRevision(firstClaim.getSourceRevision())
                .parseRequestSnapshot(requestSnapshot)
                .build())).isTrue();

        jdbc.update("""
                update ingestion_task_item
                set lease_until = timestampadd(second, -1, current_timestamp(6))
                where id = 9201
                """);
        IngestionTaskItem secondClaim = transaction.execute(status ->
                repository.claimOne("9201", 60).orElseThrow());
        assertThat(secondClaim).isNotNull();
        assertThat(secondClaim.getStageAttempt()).isEqualTo(2);
        assertThat(secondClaim.getStageRetryCount()).isEqualTo(1);
        assertThat(secondClaim.getLeaseToken()).isNotEqualTo(firstClaim.getLeaseToken());
        assertThat(secondClaim.getStageStartedAt()).isEqualTo(firstClaim.getStageStartedAt());
        assertThat(secondClaim.getDoclingJobId()).isEqualTo("job-1");
        assertThat(secondClaim.getParseRequestSnapshot()).isEqualTo(requestSnapshot);

        boolean staleTransitioned = Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(
                        transition(firstClaim, IngestionExecutionStage.PARSE_WAIT, requestSnapshot))));
        assertThat(staleTransitioned).isFalse();

        // Expiry alone is not a fence. If nobody reclaimed the row, the holder
        // may still commit using the unchanged token and stage attempt.
        jdbc.update("""
                update ingestion_task_item
                set lease_until = timestampadd(second, -1, current_timestamp(6))
                where id = 9201
                """);
        boolean currentTransitioned = Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(
                        transition(secondClaim, IngestionExecutionStage.PARSE_WAIT, requestSnapshot))));
        assertThat(currentTransitioned).isTrue();

        IngestionTaskItem transitioned = repository.findItem("1", "9200", "9201").orElseThrow();
        assertThat(transitioned.getExecutionStage()).isEqualTo(IngestionExecutionStage.PARSE_WAIT);
        assertThat(transitioned.getStageAttempt()).isZero();
        assertThat(transitioned.getStageRetryCount()).isEqualTo(1);
        assertThat(transitioned.getStageStartedAt()).isNotNull();
        assertThat(transitioned.getStageStartedAt()).isAfterOrEqualTo(firstClaim.getStageStartedAt());
        assertThat(transitioned.getNextActionAt()).isNotNull();
        assertThat(transitioned.getLeaseToken()).isNull();
        assertThat(transitioned.getLeaseUntil()).isNull();
        assertThat(transitioned.getDoclingJobId()).isEqualTo("job-1");
        assertThat(transitioned.getParseRequestSnapshot()).isEqualTo(requestSnapshot);
    }

    @Test
    void explicitRetry_shouldIncrementEpochAndClearPreviousExecutionState() {
        insertTask(jdbc, "9400", "retry-user");
        jdbc.update("""
                insert into ingestion_task_item (
                    id, task_id, kb_id, asset_id, file_name,
                    parse_attempt, docling_request_id, docling_job_id, source_revision,
                    execution_stage, execution_epoch, stage_attempt, stage_retry_count,
                    stage_started_at, next_action_at, lease_token, lease_until,
                    parse_request_snapshot, parse_result_object_key, embedding_result_object_key,
                    stage, status, progress, error_code, error_message,
                    created_at, updated_at, finished_at
                ) values (
                    9401, 9400, 1, 9501, 'failed.pdf',
                    3, '9400:9401:3', 'job-old', ?,
                    'FAILED', 4, 7, 2, current_timestamp(6), null,
                    'lease-old', timestampadd(second, 60, current_timestamp(6)),
                    json_object('fileName', 'failed.pdf'), 'parse/old.json.gz', 'embed/old.json.gz',
                    'PARSE', 'FAILED', 20, 'PARSE_FAILED', 'old failure',
                    current_timestamp(6), current_timestamp(6), current_timestamp(6)
                )
                """, "v1:" + "b".repeat(64));

        assertThat(repository.resetFailedItem(
                "1", "9400", "9401", 3, 4, "9400:9401:4", LocalDateTime.now()))
                .isTrue();

        IngestionTaskItem reset = repository.findItem("1", "9400", "9401").orElseThrow();
        assertThat(reset.getExecutionEpoch()).isEqualTo(5L);
        assertThat(reset.getExecutionStage()).isEqualTo(IngestionExecutionStage.PARSE_SUBMIT);
        assertThat(reset.getStageAttempt()).isZero();
        assertThat(reset.getStageRetryCount()).isZero();
        assertThat(reset.getStageStartedAt()).isNull();
        assertThat(reset.getNextActionAt()).isNotNull();
        assertThat(reset.getLeaseToken()).isNull();
        assertThat(reset.getLeaseUntil()).isNull();
        assertThat(reset.getParseRequestSnapshot()).isNull();
        assertThat(reset.getParseResultObjectKey()).isNull();
        assertThat(reset.getEmbeddingResultObjectKey()).isNull();
        assertThat(reset.getParseAttempt()).isEqualTo(4);
        assertThat(reset.getDoclingRequestId()).isEqualTo("9400:9401:4");
        assertThat(reset.getDoclingJobId()).isNull();
        assertThat(reset.getStatus()).isEqualTo(IngestionTaskItemStatus.PENDING);
    }

    @Test
    void stageTransitionAndTaskSummary_shouldRollbackWhenAssetProjectionFails() {
        insertTask(jdbc, "9600", "rollback-user");
        jdbc.update("""
                insert into ingestion_task_item (
                    id, task_id, kb_id, asset_id, file_name,
                    parse_attempt, docling_request_id, source_revision,
                    execution_stage, execution_epoch, stage_attempt, stage_retry_count,
                    stage_started_at, lease_token, lease_until,
                    parse_result_object_key, stage, status, progress,
                    created_at, updated_at
                ) values (
                    9601, 9600, 1, 9701, 'rollback.pdf',
                    1, '9600:9601:1', ?,
                    'EMBED', 1, 2, 0,
                    current_timestamp(6), 'lease-rollback',
                    timestampadd(second, 60, current_timestamp(6)),
                    'parse/rollback.json.gz', 'EMBED', 'RUNNING', 55,
                    current_timestamp(6), current_timestamp(6)
                )
                """, "v1:" + "c".repeat(64));
        IngestionTaskItem claimed =
                repository.findItem("1", "9600", "9601").orElseThrow();
        IngestionClaimTransition advance =
                transition(claimed, IngestionExecutionStage.INDEX, null);
        AssetRepository failingAssetRepository = mock(AssetRepository.class);
        when(failingAssetRepository.updateStatuses(
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("asset projection failed"));
        IngestionStageTransactionCoordinator coordinator =
                new IngestionStageTransactionCoordinator(repository, failingAssetRepository);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                coordinator.transitionAndUpdateAssetStatus(
                        advance,
                        Asset.builder().id("9701").kbId("1").build(),
                        "SUCCESS",
                        "RUNNING")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("asset projection failed");

        IngestionTaskItem rolledBack =
                repository.findItem("1", "9600", "9601").orElseThrow();
        assertThat(rolledBack.getExecutionStage()).isEqualTo(IngestionExecutionStage.EMBED);
        assertThat(rolledBack.getStageAttempt()).isEqualTo(2);
        assertThat(rolledBack.getLeaseToken()).isEqualTo("lease-rollback");
        assertThat(jdbc.queryForObject(
                "select status from ingestion_task where id = 9600", String.class))
                .isEqualTo("PENDING");
    }

    private IngestionClaimTransition transition(IngestionTaskItem claim,
                                                IngestionExecutionStage nextStage,
                                                String requestSnapshot) {
        LocalDateTime transitionAt = LocalDateTime.now();
        return IngestionClaimTransition.builder()
                .itemId(claim.getId())
                .taskId(claim.getTaskId())
                .kbId(claim.getKbId())
                .executionEpoch(claim.getExecutionEpoch())
                .expectedExecutionStage(claim.getExecutionStage())
                .expectedStageAttempt(claim.getStageAttempt())
                .leaseToken(claim.getLeaseToken())
                .nextExecutionStage(nextStage)
                .nextStageAttempt(0)
                .nextStageRetryCount(claim.getStageRetryCount())
                .nextStageStartedAt(transitionAt)
                .nextActionAt(transitionAt.plusSeconds(1))
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(30)
                .parseAttempt(claim.getParseAttempt())
                .doclingRequestId(claim.getDoclingRequestId())
                .doclingJobId("job-1")
                .sourceRevision(claim.getSourceRevision())
                .parseRequestSnapshot(requestSnapshot)
                .parseResultObjectKey(claim.getParseResultObjectKey())
                .embeddingResultObjectKey(claim.getEmbeddingResultObjectKey())
                .errorCode(null)
                .errorMessage(null)
                .finishedAt(null)
                .updatedBy(claim.getTaskCreatedBy())
                .updatedAt(transitionAt)
                .build();
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void insertTask(JdbcTemplate jdbc, String taskId, String createdBy) {
        jdbc.update("""
                insert into ingestion_task (
                    id, kb_id, source_type, status, total_count, success_count,
                    failure_count, running_count, created_by, updated_by, created_at, updated_at
                ) values (?, 1, 'UPLOAD', 'PENDING', 1, 0, 0, 0, ?, ?, now(), now())
                """, taskId, createdBy, createdBy);
    }

    private static void insertPreV10Item(JdbcTemplate jdbc, String itemId,
                                         String stage, String status, int progress) {
        jdbc.update("""
                insert into ingestion_task_item (
                    id, task_id, kb_id, file_name, parse_attempt,
                    stage, status, progress, created_at, updated_at
                ) values (?, ?, 1, ?, 1, ?, ?, ?, '2026-01-01 00:00:00', '2026-01-01 00:01:00')
                """, itemId, BACKFILL_TASK_ID, itemId + ".pdf", stage, status, progress);
    }

    private void assertBackfill(java.util.Map<String, Object> row, String itemId,
                                String expectedStage, boolean scheduled) {
        assertThat(row.get("id").toString()).isEqualTo(itemId);
        assertThat(row.get("execution_stage")).isEqualTo(expectedStage);
        if (scheduled) {
            assertThat(row.get("stage")).isEqualTo("PARSE");
            assertThat(((Number) row.get("progress")).intValue()).isEqualTo(20);
        }
        assertThat(((Number) row.get("execution_epoch")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("stage_attempt")).intValue()).isZero();
        assertThat(((Number) row.get("stage_retry_count")).intValue()).isZero();
        assertThat(row.get("next_action_at") != null).isEqualTo(scheduled);
        assertThat(row.get("lease_token")).isNull();
        assertThat(row.get("lease_until")).isNull();
    }

    private List<String> indexColumns(String indexName) {
        return jdbc.queryForList("""
                select column_name
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'ingestion_task_item'
                  and index_name = ?
                order by seq_in_index
                """, String.class, indexName);
    }
}
