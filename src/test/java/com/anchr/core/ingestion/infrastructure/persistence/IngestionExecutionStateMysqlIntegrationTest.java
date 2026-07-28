package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.application.impl.IngestionStageTransactionCoordinator;
import com.anchr.core.ingestion.domain.model.IngestionArtifactReference;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionKind;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjection;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionRetryConflictException;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class IngestionExecutionStateMysqlIntegrationTest {

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
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from ingestion_item_artifact");
        jdbc.update("update ingestion_task_item set current_execution_id = null");
        jdbc.update("delete from ingestion_item_execution");
        jdbc.update("delete from ingestion_item_parse_attempt");
        jdbc.update("delete from ingestion_task_item");
        jdbc.update("delete from ingestion_task");

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/ingestion/IngestionTaskMapper.xml"));
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sessionFactory);
        mapper = sessionTemplate.getMapper(IngestionTaskMapper.class);
        repository = new IngestionTaskRepositoryImpl(mapper);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @Test
    void migration_shouldLeaveOnlyStableItemProjectionAndNormalizedOwnershipIndexes() {
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
                "target_index_generation",
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
                order by index_name
                """, String.class);
        assertThat(itemIndexes)
                .containsExactlyInAnyOrder(
                        "PRIMARY",
                        "idx_ingestion_item_current_execution",
                        "idx_ingestion_item_asset_generation",
                        "idx_task_item_asset",
                        "idx_task_item_task")
                .doesNotContain(
                        "idx_ingestion_item_claim",
                        "idx_ingestion_task_claim",
                        "idx_task_item_kb_status");

        assertThat(jdbc.queryForList("""
                select index_name
                from information_schema.statistics
                where table_schema = database()
                  and (
                    (table_name = 'ingestion_item_parse_attempt'
                      and index_name = 'uk_ingestion_parse_attempt_id_item')
                    or
                    (table_name = 'ingestion_item_execution'
                      and index_name = 'uk_ingestion_execution_id_item')
                  )
                """, String.class)).isEmpty();

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
    }

    @Test
    void ownershipGuards_shouldRejectCrossItemExecutionAndParseAttemptPointers() {
        savePendingItem(
                "9100", "9101", "9151", "first.pdf",
                IngestionExecutionStage.PARSE_SUBMIT, null,
                "v1:" + "1".repeat(64));
        savePendingItem(
                "9110", "9111", "9152", "second.pdf",
                IngestionExecutionStage.PARSE_SUBMIT, null,
                "v1:" + "2".repeat(64));
        long firstExecutionId = currentExecutionId("9101");
        long secondExecutionId = currentExecutionId("9111");
        long secondAttemptId = parseAttemptId(secondExecutionId);

        jdbc.update(
                "update ingestion_task_item set current_execution_id = null where id = 9101");
        assertThat(mapper.pointItemToExecution(
                "9101", secondExecutionId, LocalDateTime.now())).isZero();
        jdbc.update(
                "update ingestion_task_item set current_execution_id = ? where id = 9101",
                firstExecutionId);

        jdbc.update(
                "update ingestion_item_execution set parse_attempt_id = ? where id = ?",
                secondAttemptId, firstExecutionId);
        assertThat(repository.listClaimableItemIds(10)).doesNotContain("9101");
        IngestionTaskItem unexpectedClaim = transaction.execute(status ->
                repository.claimOne("9101", 60).orElse(null));
        assertThat(unexpectedClaim).isNull();
        assertThat(execution(firstExecutionId).get("lease_token")).isNull();

        LocalDateTime failedAt = LocalDateTime.now();
        jdbc.update("""
                update ingestion_item_execution
                set execution_status = 'FAILED',
                    finished_at = ?,
                    updated_at = ?
                where id = ?
                """, failedAt, failedAt, firstExecutionId);
        jdbc.update("""
                update ingestion_task_item
                set status = 'FAILED',
                    finished_at = ?,
                    updated_at = ?
                where id = 9101
                """, failedAt, failedAt);
        assertThat(mapper.findRetryItem("1", "9100", "9101")).isEmpty();
        assertThat(mapper.listFailedItems("1", "9100"))
                .extracting(FailedItemRetryRecord::getItemId)
                .doesNotContain("9101");
        assertThat(Boolean.TRUE.equals(transaction.execute(status ->
                repository.resetFailedItem(
                        "1", "9100", "9101", 1, 2,
                        "9100:9101:2", LocalDateTime.now())))).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*)
                from ingestion_item_execution
                where item_id = 9101
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from ingestion_item_parse_attempt
                where item_id = 9101
                """, Integer.class)).isEqualTo(1);

        assertThat(mapper.resetFailedItemPointer(
                "1",
                "9100",
                "9101",
                firstExecutionId,
                secondExecutionId,
                IngestionPublicProjectionPolicy.explicitRetry(),
                LocalDateTime.now())).isZero();
        assertThat(currentExecutionId("9101")).isEqualTo(firstExecutionId);
    }

    @Test
    void heldClaim_shouldLoseEveryFenceWhenParseAttemptOwnershipBreaks() {
        savePendingItem(
                "9120", "9121", "9161", "held.pdf",
                IngestionExecutionStage.PARSE_SUBMIT, null,
                "v1:" + "3".repeat(64));
        savePendingItem(
                "9130", "9131", "9162", "other.pdf",
                IngestionExecutionStage.PARSE_SUBMIT, null,
                "v1:" + "4".repeat(64));
        IngestionTaskItem claim = transaction.execute(status ->
                repository.claimOne("9121", 60).orElseThrow());
        long claimedExecutionId = currentExecutionId("9121");
        long otherAttemptId = parseAttemptId(currentExecutionId("9131"));

        jdbc.update(
                "update ingestion_item_execution set parse_attempt_id = ? where id = ?",
                otherAttemptId, claimedExecutionId);

        assertThat(repository.renewClaim(
                claim.getId(),
                claim.getExecutionEpoch(),
                claim.getExecutionStage(),
                claim.getClaimVersion(),
                claim.getLeaseToken(),
                60)).isFalse();
        assertThat(Boolean.TRUE.equals(transaction.execute(status ->
                repository.isClaimCurrentForUpdate(
                        claim.getId(),
                        claim.getExecutionEpoch(),
                        claim.getExecutionStage(),
                        claim.getClaimVersion(),
                        claim.getLeaseToken())))).isFalse();
        assertThat(Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(transition(
                        claim, IngestionExecutionStage.PARSE_WAIT, "{}"))))).isFalse();
        assertThat(execution(claimedExecutionId))
                .containsEntry("phase", "PARSE_SUBMIT")
                .containsEntry("execution_status", "ACTIVE");
    }

    @Test
    void claimAndTransition_shouldFenceStaleWorkerAndKeepClaimVersionMonotonicAcrossPhases() {
        savePendingItem(
                "9200", "9201", "9301", "sample.pdf",
                IngestionExecutionStage.PARSE_SUBMIT, null,
                "v1:" + "a".repeat(64));
        long executionId = currentExecutionId("9201");

        assertThat(repository.listClaimableItemIds(10)).containsExactly("9201");
        assertThat(repository.listClaimableItemIds("9200", 10)).containsExactly("9201");

        IngestionTaskItem firstClaim = transaction.execute(status ->
                repository.claimOne("9201", 60).orElseThrow());
        assertThat(firstClaim).isNotNull();
        assertThat(firstClaim.getTaskCreatedBy()).isEqualTo("worker-user");
        assertThat(firstClaim.getStatus()).isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(firstClaim.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(firstClaim.getProgress()).isEqualTo(20);
        assertThat(firstClaim.getClaimVersion()).isEqualTo(1);
        assertThat(firstClaim.getStageStartedAt()).isNotNull();
        assertThat(firstClaim.getLeaseToken()).isNotBlank();
        assertThat(firstClaim.getLeaseUntil()).isAfter(firstClaim.getStageStartedAt());
        assertThat(jdbc.queryForObject(
                "select status from ingestion_task where id = 9200", String.class))
                .isEqualTo("RUNNING");

        String requestSnapshot = """
                {"contractVersion":2,"fileName":"sample.pdf","options":{"chunk":true}}
                """.trim();
        assertThat(repository.updateClaimContext(IngestionClaimContext.builder()
                .itemId(firstClaim.getId())
                .executionEpoch(firstClaim.getExecutionEpoch())
                .expectedExecutionStage(firstClaim.getExecutionStage())
                .claimVersion(firstClaim.getClaimVersion())
                .leaseToken(firstClaim.getLeaseToken())
                .parseAttempt(firstClaim.getParseAttempt())
                .doclingRequestId(firstClaim.getDoclingRequestId())
                .doclingJobId("job-1")
                .sourceRevision(firstClaim.getSourceRevision())
                .parseRequestSnapshot(requestSnapshot)
                .build())).isTrue();

        expireLease(executionId);
        IngestionTaskItem secondClaim = transaction.execute(status ->
                repository.claimOne("9201", 60).orElseThrow());
        assertThat(secondClaim).isNotNull();
        assertThat(secondClaim.getClaimVersion()).isEqualTo(2);
        assertThat(secondClaim.getStageRetryCount()).isEqualTo(1);
        assertThat(secondClaim.getLeaseToken()).isNotEqualTo(firstClaim.getLeaseToken());
        assertThat(secondClaim.getStageStartedAt()).isEqualTo(firstClaim.getStageStartedAt());
        assertThat(secondClaim.getDoclingJobId()).isEqualTo("job-1");
        assertThat(secondClaim.getParseRequestSnapshot()).isEqualTo(requestSnapshot);

        IngestionClaimTransition staleWithArtifact = transition(
                firstClaim, IngestionExecutionStage.PARSE_WAIT, requestSnapshot).toBuilder()
                .parseResultObjectKey("parse/stale.json.gz")
                .parseResultSha256("a".repeat(64))
                .build();
        boolean staleTransitioned = Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(staleWithArtifact)));
        assertThat(staleTransitioned).isFalse();
        assertThat(artifacts(executionId)).isEmpty();

        // Lease expiry makes the row reclaimable but is not itself a fencing
        // event. The current holder still owns the unchanged token/version.
        expireLease(executionId);
        boolean currentTransitioned = Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(
                        transition(secondClaim, IngestionExecutionStage.PARSE_WAIT, requestSnapshot))));
        assertThat(currentTransitioned).isTrue();

        Map<String, Object> transitionedExecution = execution(executionId);
        assertThat(transitionedExecution.get("phase")).isEqualTo("PARSE_WAIT");
        assertThat(((Number) transitionedExecution.get("claim_version")).longValue())
                .isEqualTo(2L);
        assertThat(((Number) transitionedExecution.get("phase_retry_count")).intValue())
                .isEqualTo(1);
        assertThat(transitionedExecution.get("lease_token")).isNull();
        assertThat(transitionedExecution.get("lease_until")).isNull();
        assertThat(transitionedExecution.get("next_action_at")).isNotNull();

        Map<String, Object> parseAttempt = parseAttemptForExecution(executionId);
        assertThat(parseAttempt.get("job_id")).isEqualTo("job-1");
        assertThat(parseAttempt.get("request_snapshot").toString())
                .contains("\"contractVersion\": 2");

        // A new phase claim increments the same execution-wide fence instead
        // of resetting a stage-local counter to zero.
        jdbc.update("""
                update ingestion_item_execution
                set next_action_at = current_timestamp(6)
                where id = ?
                """, executionId);
        IngestionTaskItem thirdClaim = transaction.execute(status ->
                repository.claimOne("9201", 60).orElseThrow());
        assertThat(thirdClaim.getExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_WAIT);
        assertThat(thirdClaim.getClaimVersion()).isEqualTo(3);
        assertThat(thirdClaim.getStageRetryCount()).isEqualTo(1);
        assertThat(thirdClaim.getDoclingJobId()).isEqualTo("job-1");
        assertThat(thirdClaim.getParseRequestSnapshot()).isEqualTo(requestSnapshot);

        assertThat(Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(
                        transition(secondClaim, IngestionExecutionStage.PARSE_PERSIST,
                                requestSnapshot)))))
                .isFalse();
    }

    @Test
    void parseArtifact_shouldRegisterReuseAndRollbackConflictingMetadata() {
        savePendingItem(
                "9250", "9251", "9351", "artifact.pdf",
                IngestionExecutionStage.PARSE_PERSIST, null,
                "v1:" + "b".repeat(64));
        long executionId = currentExecutionId("9251");
        IngestionTaskItem parseClaim = transaction.execute(status ->
                repository.claimOne("9251", 60).orElseThrow());

        String parseKey = "ingestion/9250/9251/parse/result.json.gz";
        String parseSha = "a".repeat(64);
        IngestionClaimTransition parseProduced = transition(
                parseClaim, IngestionExecutionStage.EMBED, null).toBuilder()
                .parseResultObjectKey(parseKey)
                .parseResultSha256(parseSha)
                .build();
        assertThat(Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(parseProduced)))).isTrue();

        assertThat(artifacts(executionId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("artifact_type")).isEqualTo("PARSE_RESULT");
                    assertThat(row.get("provenance")).isEqualTo("PRODUCED");
                    assertThat(row.get("object_key")).isEqualTo(parseKey);
                    assertThat(row.get("content_sha256")).isEqualTo(parseSha);
                    assertThat(((Number) row.get("producer_claim_version")).longValue())
                            .isEqualTo(parseClaim.getClaimVersion());
                });

        jdbc.update("""
                update ingestion_item_execution
                set next_action_at = current_timestamp(6)
                where id = ?
                """, executionId);
        IngestionTaskItem embedClaim = transaction.execute(status ->
                repository.claimOne("9251", 60).orElseThrow());
        Map<String, Object> executionBefore = execution(executionId);
        Map<String, Object> itemBefore = projectedItem("9251");
        Map<String, Object> taskBefore = taskSummary("9250");
        List<Map<String, Object>> artifactsBefore = artifacts(executionId);
        IngestionClaimTransition conflicting = transition(
                embedClaim, IngestionExecutionStage.EMBED, null).toBuilder()
                .parseResultObjectKey("ingestion/9250/9251/parse/conflict.json.gz")
                .parseResultSha256("c".repeat(64))
                .build();

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                repository.transitionClaim(conflicting)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different metadata");

        assertThat(execution(executionId)).isEqualTo(executionBefore);
        assertThat(projectedItem("9251")).isEqualTo(itemBefore);
        assertThat(taskSummary("9250")).isEqualTo(taskBefore);
        assertThat(artifacts(executionId)).isEqualTo(artifactsBefore);
    }

    @Test
    void embedToIndexHandoff_shouldRetainTheCurrentLease() {
        savePendingItem(
                "9260", "9261", "9361", "handoff.pdf",
                IngestionExecutionStage.EMBED,
                "ingestion/9260/9261/parse/result.json.gz",
                "v1:" + "c".repeat(64));
        long executionId = currentExecutionId("9261");
        IngestionTaskItem embedClaim = transaction.execute(status ->
                repository.claimOne("9261", 60).orElseThrow());

        IngestionClaimTransition handoff = transition(
                embedClaim, IngestionExecutionStage.INDEX, null).toBuilder()
                .retainLease(true)
                .build();
        assertThat(Boolean.TRUE.equals(transaction.execute(status ->
                repository.transitionClaim(handoff)))).isTrue();

        Map<String, Object> execution = execution(executionId);
        assertThat(execution.get("phase")).isEqualTo("INDEX");
        assertThat(execution.get("lease_token")).isEqualTo(embedClaim.getLeaseToken());
        assertThat(execution.get("lease_until")).isNotNull();
        assertThat(repository.claimOne("9261", 60)).isEmpty();
        assertThat(repository.renewClaim(
                "9261",
                embedClaim.getExecutionEpoch(),
                IngestionExecutionStage.INDEX,
                embedClaim.getClaimVersion(),
                embedClaim.getLeaseToken(),
                60)).isTrue();
    }

    @Test
    void reembedFirstClaim_shouldPreservePublicProgressWhileMovingToParse() {
        LocalDateTime now = LocalDateTime.now().minusSeconds(2);
        IngestionPublicProjection initial =
                IngestionPublicProjectionPolicy.pending(IngestionSourceType.REEMBED);
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id("9301")
                .taskId("9300")
                .kbId("1")
                .assetId("9351")
                .fileName("reembed.pdf")
                .fileHash("hash-reembed")
                .parseAttempt(1)
                .doclingRequestId("9300:9301:1")
                .sourceRevision("v1:" + "f".repeat(64))
                .executionStage(IngestionExecutionStage.PARSE_SUBMIT)
                .executionEpoch(1L)
                .nextActionAt(now)
                .stage(initial.stage())
                .status(initial.status())
                .progress(initial.progress())
                .createdAt(now)
                .updatedAt(now)
                .build();
        IngestionTask task = IngestionTask.builder()
                .id("9300")
                .kbId("1")
                .sourceType(IngestionSourceType.REEMBED)
                .initialExecutionKind(IngestionExecutionKind.REEMBED)
                .status(IngestionTaskStatus.PENDING)
                .totalCount(1)
                .createdBy("reembed-user")
                .updatedBy("reembed-user")
                .createdAt(now)
                .updatedAt(now)
                .items(List.of(item))
                .build();
        transaction.executeWithoutResult(ignored -> repository.save(task));

        IngestionTaskItem before = repository.findItem("1", "9300", "9301")
                .orElseThrow();
        assertThat(before.getStage()).isEqualTo(IngestionStage.EMBED);
        assertThat(before.getStatus()).isEqualTo(IngestionTaskItemStatus.PENDING);
        assertThat(before.getProgress()).isEqualTo(60);

        IngestionTaskItem claimed = transaction.execute(status ->
                repository.claimOne("9301", 60).orElseThrow());
        assertThat(claimed.getExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_SUBMIT);
        assertThat(claimed.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(claimed.getStatus()).isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(claimed.getProgress()).isEqualTo(60);
        assertThat(execution(currentExecutionId("9301")))
                .containsEntry("execution_kind", "REEMBED");

        IngestionTaskItem visible = repository.findItem("1", "9300", "9301")
                .orElseThrow();
        assertThat(visible.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(visible.getStatus()).isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(visible.getProgress()).isEqualTo(60);
    }

    @Test
    void explicitRetry_shouldCreateNewExecutionAndPreserveFailedExecutionHistory() {
        savePendingItem(
                "9400", "9401", "9501", "failed.pdf",
                IngestionExecutionStage.PARSE_SUBMIT, null,
                "v1:" + "b".repeat(64));
        long failedExecutionId = currentExecutionId("9401");
        long failedParseAttemptId = parseAttemptId(failedExecutionId);
        LocalDateTime failedAt = LocalDateTime.now().minusSeconds(1);

        jdbc.update("""
                update ingestion_item_parse_attempt
                set attempt_no = 3,
                    status = 'FAILED',
                    request_id = '9400:9401:3',
                    job_id = 'job-old',
                    request_snapshot = json_object('fileName', 'failed.pdf'),
                    updated_at = ?,
                    finished_at = ?
                where id = ?
                """, failedAt, failedAt, failedParseAttemptId);
        jdbc.update("""
                update ingestion_item_execution
                set execution_epoch = 4,
                    execution_status = 'FAILED',
                    phase = 'PARSE_WAIT',
                    claim_version = 7,
                    phase_retry_count = 2,
                    phase_started_at = ?,
                    next_action_at = null,
                    lease_token = null,
                    lease_until = null,
                    error_code = 'PARSE_FAILED',
                    error_message = 'old failure',
                    updated_at = ?,
                    finished_at = ?
                where id = ?
                """, failedAt.minusMinutes(1), failedAt, failedAt, failedExecutionId);
        jdbc.update("""
                update ingestion_task_item
                set stage = 'PARSE',
                    status = 'FAILED',
                    progress = 20,
                    error_code = 'PARSE_FAILED',
                    error_message = 'old failure',
                    updated_at = ?,
                    finished_at = ?
                where id = 9401
                """, failedAt, failedAt);
        insertProducedArtifact(
                failedExecutionId, "PARSE_RESULT", "parse/old.json.gz", "c".repeat(64), 6);

        boolean reset = Boolean.TRUE.equals(transaction.execute(status ->
                repository.resetFailedItem(
                        "1", "9400", "9401", 3, 4,
                        "9400:9401:4", LocalDateTime.now())));
        assertThat(reset).isTrue();

        IngestionTaskItem current = repository.findItem("1", "9400", "9401").orElseThrow();
        assertThat(current.getStage()).isEqualTo(IngestionStage.UPLOAD);
        assertThat(current.getStatus()).isEqualTo(IngestionTaskItemStatus.PENDING);
        assertThat(current.getProgress()).isZero();
        assertThat(current.getErrorCode()).isNull();
        assertThat(current.getErrorMessage()).isNull();
        assertThat(current.getFinishedAt()).isNull();

        long currentExecutionId = currentExecutionId("9401");
        assertThat(currentExecutionId).isNotEqualTo(failedExecutionId);
        Map<String, Object> currentExecution = execution(currentExecutionId);
        assertThat(currentExecution.get("execution_kind")).isEqualTo("EXPLICIT_RETRY");
        assertThat(currentExecution.get("execution_status")).isEqualTo("ACTIVE");
        assertThat(currentExecution.get("phase")).isEqualTo("PARSE_SUBMIT");
        assertThat(((Number) currentExecution.get("claim_version")).longValue()).isZero();
        assertThat(((Number) currentExecution.get("phase_retry_count")).intValue()).isZero();
        assertThat(currentExecution.get("next_action_at")).isNotNull();
        assertThat(currentExecution.get("lease_token")).isNull();
        assertThat(currentExecution.get("lease_until")).isNull();
        assertThat(currentExecution.get("error_code")).isNull();
        assertThat(currentExecution.get("error_message")).isNull();

        Map<String, Object> newParseAttempt = parseAttemptForExecution(currentExecutionId);
        assertThat(((Number) newParseAttempt.get("attempt_no")).intValue()).isEqualTo(4);
        assertThat(newParseAttempt.get("request_id")).isEqualTo("9400:9401:4");
        assertThat(newParseAttempt.get("job_id")).isNull();
        assertThat(newParseAttempt.get("request_snapshot")).isNull();
        assertThat(newParseAttempt.get("status")).isEqualTo("ACTIVE");
        FailedItemRetryRecord retryExecution =
                mapper.findRetryItem("1", "9400", "9401").orElseThrow();
        assertThat(retryExecution.getExecutionEpoch()).isEqualTo(5L);
        assertThat(retryExecution.getExecutionStatus()).isEqualTo("ACTIVE");
        assertThat(retryExecution.getParseAttemptNo()).isEqualTo(4);

        Map<String, Object> failedExecution = execution(failedExecutionId);
        assertThat(failedExecution.get("execution_status")).isEqualTo("FAILED");
        assertThat(failedExecution.get("phase")).isEqualTo("PARSE_WAIT");
        assertThat(((Number) failedExecution.get("execution_epoch")).longValue()).isEqualTo(4L);
        assertThat(((Number) failedExecution.get("claim_version")).longValue()).isEqualTo(7L);
        assertThat(((Number) failedExecution.get("phase_retry_count")).intValue()).isEqualTo(2);
        assertThat(failedExecution.get("error_code")).isEqualTo("PARSE_FAILED");
        assertThat(failedExecution.get("error_message")).isEqualTo("old failure");
        assertThat(failedExecution.get("finished_at")).isNotNull();

        assertThat(parseAttemptForExecution(failedExecutionId))
                .containsEntry("status", "FAILED")
                .containsEntry("job_id", "job-old");
        assertThat(jdbc.queryForList("""
                select artifact_type, object_key, content_sha256, producer_claim_version
                from ingestion_item_artifact
                where execution_id = ?
                order by artifact_type
                """, failedExecutionId))
                .extracting(row -> row.get("object_key"))
                .containsExactly("parse/old.json.gz");

        assertThat(jdbc.queryForObject("""
                select count(*)
                from ingestion_item_execution ie
                inner join ingestion_task_item iti
                    on iti.current_execution_id = ie.id
                where ie.id = ?
                  and ie.execution_status = 'ACTIVE'
                """, Integer.class, failedExecutionId))
                .isZero();
        assertThat(repository.listClaimableItemIds(10)).containsExactly("9401");

        IngestionTaskItem retriedClaim = transaction.execute(status ->
                repository.claimOne("9401", 60).orElseThrow());
        assertThat(retriedClaim.getExecutionStage())
                .isEqualTo(IngestionExecutionStage.PARSE_SUBMIT);
        assertThat(retriedClaim.getClaimVersion()).isEqualTo(1L);
        assertThat(retriedClaim.getStage()).isEqualTo(IngestionStage.PARSE);
        assertThat(retriedClaim.getStatus()).isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(retriedClaim.getProgress()).isEqualTo(20);

        assertThat(Boolean.TRUE.equals(transaction.execute(status ->
                repository.resetFailedItem(
                        "1", "9400", "9401", 3, 4,
                        "9400:9401:4", LocalDateTime.now()))))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_execution where item_id = 9401",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_parse_attempt where item_id = 9401",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void retryPointerConflict_shouldRollbackPreparedAttemptAndExecution() {
        savePendingItem(
                "9500", "9501", "9551", "conflict.pdf",
                IngestionExecutionStage.PARSE_SUBMIT, null,
                "v1:" + "9".repeat(64));
        long executionId = currentExecutionId("9501");
        long attemptId = parseAttemptId(executionId);
        LocalDateTime failedAt = LocalDateTime.now();
        jdbc.update("""
                update ingestion_item_parse_attempt
                set status = 'FAILED', finished_at = ?, updated_at = ?
                where id = ?
                """, failedAt, failedAt, attemptId);
        jdbc.update("""
                update ingestion_item_execution
                set execution_status = 'FAILED',
                    error_code = 'PARSE_FAILED',
                    finished_at = ?,
                    updated_at = ?
                where id = ?
                """, failedAt, failedAt, executionId);
        jdbc.update("""
                update ingestion_task_item
                set status = 'FAILED',
                    error_code = 'PARSE_FAILED',
                    finished_at = ?,
                    updated_at = ?
                where id = 9501
                """, failedAt, failedAt);

        IngestionTaskMapper losingMapper = (IngestionTaskMapper) Proxy.newProxyInstance(
                IngestionTaskMapper.class.getClassLoader(),
                new Class<?>[]{IngestionTaskMapper.class},
                (proxy, method, args) -> {
                    if ("resetFailedItemPointer".equals(method.getName())) {
                        return 0;
                    }
                    try {
                        return method.invoke(mapper, args);
                    } catch (InvocationTargetException invocationFailure) {
                        throw invocationFailure.getTargetException();
                    }
                });
        IngestionTaskRepositoryImpl losingRepository =
                new IngestionTaskRepositoryImpl(losingMapper);

        assertThatThrownBy(() -> transaction.execute(status ->
                losingRepository.resetFailedItem(
                        "1", "9500", "9501", 1, 2,
                        "9500:9501:2", LocalDateTime.now())))
                .isInstanceOf(IngestionRetryConflictException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_parse_attempt where item_id = 9501",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_execution where item_id = 9501",
                Integer.class)).isEqualTo(1);
        assertThat(currentExecutionId("9501")).isEqualTo(executionId);
    }

    @Test
    void stageTransitionAndTaskSummary_shouldRollbackWhenAssetProjectionFails() {
        savePendingItem(
                "9600", "9601", "9701", "rollback.pdf",
                IngestionExecutionStage.EMBED,
                "parse/rollback.json.gz",
                "v1:" + "e".repeat(64));
        long executionId = currentExecutionId("9601");
        IngestionTaskItem claimed = transaction.execute(status ->
                repository.claimOne("9601", 60).orElseThrow());

        Map<String, Object> executionBefore = execution(executionId);
        Map<String, Object> itemBefore = projectedItem("9601");
        Map<String, Object> taskBefore = taskSummary("9600");
        List<Map<String, Object>> artifactsBefore = artifacts(executionId);

        LocalDateTime completedAt = LocalDateTime.now();
        IngestionClaimTransition complete = transition(
                claimed, IngestionExecutionStage.COMPLETE, null).toBuilder()
                .nextActionAt(null)
                .stage(IngestionStage.ASKABLE)
                .status(IngestionTaskItemStatus.SUCCESS)
                .progress(100)
                .finishedAt(completedAt)
                .updatedAt(completedAt)
                .build();
        AssetRepository failingAssetRepository = mock(AssetRepository.class);
        when(failingAssetRepository.updateStatuses(
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("asset projection failed"));
        IngestionStageTransactionCoordinator coordinator =
                new IngestionStageTransactionCoordinator(
                        repository,
                        failingAssetRepository,
                        mock(com.anchr.core.ingestion.application.impl.IngestionArtifactCleanupRecorder.class),
                        mock(com.anchr.core.kb.application.support.AssetCleanupOutboxRecorder.class));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                coordinator.transitionAndUpdateAssetStatus(
                        complete,
                        Asset.builder().id("9701").kbId("1").build(),
                        "SUCCESS",
                        "RUNNING")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("asset projection failed");

        assertThat(execution(executionId)).isEqualTo(executionBefore);
        assertThat(projectedItem("9601")).isEqualTo(itemBefore);
        assertThat(taskSummary("9600")).isEqualTo(taskBefore);
        assertThat(artifacts(executionId)).isEqualTo(artifactsBefore);

        Map<String, Object> rolledBackExecution = execution(executionId);
        assertThat(rolledBackExecution.get("phase")).isEqualTo("EMBED");
        assertThat(((Number) rolledBackExecution.get("claim_version")).longValue())
                .isEqualTo(claimed.getClaimVersion());
        assertThat(repository.findItem("1", "9600", "9601").orElseThrow().getStatus())
                .isEqualTo(IngestionTaskItemStatus.RUNNING);
        assertThat(jdbc.queryForObject(
                "select status from ingestion_task where id = 9600", String.class))
                .isEqualTo("RUNNING");
    }

    private IngestionClaimTransition transition(IngestionTaskItem claim,
                                                IngestionExecutionStage nextStage,
                                                String requestSnapshot) {
        LocalDateTime transitionAt = LocalDateTime.now();
        IngestionPublicProjection projection =
                IngestionPublicProjectionPolicy.transition(
                        claim.getExecutionStage(), nextStage,
                        Math.max(claim.getProgress(), 30));
        return IngestionClaimTransition.builder()
                .itemId(claim.getId())
                .taskId(claim.getTaskId())
                .kbId(claim.getKbId())
                .executionEpoch(claim.getExecutionEpoch())
                .expectedExecutionStage(claim.getExecutionStage())
                .expectedClaimVersion(claim.getClaimVersion())
                .leaseToken(claim.getLeaseToken())
                .nextExecutionStage(nextStage)
                .nextStageRetryCount(claim.getStageRetryCount())
                .nextStageStartedAt(transitionAt)
                .nextActionAt(transitionAt.plusSeconds(1))
                .stage(projection.stage())
                .status(projection.status())
                .progress(projection.progress())
                .parseAttempt(claim.getParseAttempt())
                .doclingRequestId(claim.getDoclingRequestId())
                .doclingJobId("job-1")
                .sourceRevision(claim.getSourceRevision())
                .parseRequestSnapshot(requestSnapshot)
                .parseResultObjectKey(claim.getParseResultObjectKey())
                .errorCode(null)
                .errorMessage(null)
                .finishedAt(null)
                .updatedBy(claim.getTaskCreatedBy())
                .updatedAt(transitionAt)
                .build();
    }

    private void savePendingItem(
            String taskId,
            String itemId,
            String assetId,
            String fileName,
            IngestionExecutionStage phase,
            String parseObjectKey,
            String sourceRevision) {
        LocalDateTime now = LocalDateTime.now().minusSeconds(2);
        IngestionStage publicStage = switch (phase) {
            case EMBED -> IngestionStage.EMBED;
            case INDEX -> IngestionStage.INDEX;
            default -> IngestionStage.UPLOAD;
        };
        int progress = switch (phase) {
            case EMBED -> 55;
            case INDEX -> 75;
            default -> 0;
        };
        long initialClaimVersion = parseObjectKey == null ? 0L : 1L;
        IngestionTaskItem item = IngestionTaskItem.builder()
                .id(itemId)
                .taskId(taskId)
                .kbId("1")
                .assetId(assetId)
                .fileName(fileName)
                .fileHash("hash-" + itemId)
                .parseAttempt(1)
                .doclingRequestId(taskId + ":" + itemId + ":1")
                .sourceRevision(sourceRevision)
                .executionStage(phase)
                .executionEpoch(1L)
                .claimVersion(initialClaimVersion)
                .nextActionAt(now)
                .parseResultObjectKey(parseObjectKey)
                .parseResultArtifact(artifactReference(
                        "PARSE_RESULT", parseObjectKey, initialClaimVersion,
                        "a".repeat(64)))
                .stage(publicStage)
                .status(IngestionTaskItemStatus.PENDING)
                .progress(progress)
                .createdAt(now)
                .updatedAt(now)
                .build();
        IngestionTask task = IngestionTask.builder()
                .id(taskId)
                .kbId("1")
                .sourceType(IngestionSourceType.UPLOAD)
                .initialExecutionKind(IngestionExecutionKind.INITIAL)
                .status(IngestionTaskStatus.PENDING)
                .totalCount(1)
                .createdBy(owner(taskId))
                .updatedBy(owner(taskId))
                .createdAt(now)
                .updatedAt(now)
                .items(List.of(item))
                .build();
        transaction.executeWithoutResult(ignored -> repository.save(task));

        assertThat(jdbc.queryForObject(
                "select current_execution_id from ingestion_task_item where id = ?",
                Long.class, itemId)).isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_execution where item_id = ?",
                Integer.class, itemId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from ingestion_item_parse_attempt where item_id = ?",
                Integer.class, itemId)).isEqualTo(1);
    }

    private IngestionArtifactReference artifactReference(
            String artifactType,
            String objectKey,
            long producerClaimVersion,
            String contentSha256) {
        if (objectKey == null) {
            return null;
        }
        return IngestionArtifactReference.builder()
                .artifactType(artifactType)
                .artifactVersion(1)
                .provenance("PRODUCED")
                .producerClaimVersion(producerClaimVersion)
                .objectKey(objectKey)
                .contentSha256(contentSha256)
                .build();
    }

    private String owner(String taskId) {
        return switch (taskId) {
            case "9200" -> "worker-user";
            case "9400" -> "retry-user";
            case "9600" -> "rollback-user";
            default -> "test-user";
        };
    }

    private void expireLease(long executionId) {
        jdbc.update("""
                update ingestion_item_execution
                set lease_until = timestampadd(second, -1, current_timestamp(6))
                where id = ?
                """, executionId);
    }

    private long currentExecutionId(String itemId) {
        return jdbc.queryForObject("""
                select current_execution_id
                from ingestion_task_item
                where id = ?
                """, Long.class, itemId);
    }

    private long parseAttemptId(long executionId) {
        return jdbc.queryForObject("""
                select parse_attempt_id
                from ingestion_item_execution
                where id = ?
                """, Long.class, executionId);
    }

    private Map<String, Object> execution(long executionId) {
        return jdbc.queryForMap("""
                select execution_epoch, execution_kind, execution_status, phase,
                       claim_version, phase_retry_count, phase_started_at,
                       next_action_at, lease_token, lease_until,
                       error_code, error_message, finished_at
                from ingestion_item_execution
                where id = ?
                """, executionId);
    }

    private Map<String, Object> parseAttemptForExecution(long executionId) {
        return jdbc.queryForMap("""
                select ipa.attempt_no, ipa.status, ipa.request_id, ipa.job_id,
                       ipa.source_revision, ipa.request_snapshot, ipa.finished_at
                from ingestion_item_execution ie
                inner join ingestion_item_parse_attempt ipa
                    on ipa.id = ie.parse_attempt_id
                where ie.id = ?
                """, executionId);
    }

    private Map<String, Object> projectedItem(String itemId) {
        return jdbc.queryForMap("""
                select current_execution_id, stage, status, progress,
                       error_code, error_message, updated_at, finished_at
                from ingestion_task_item
                where id = ?
                """, itemId);
    }

    private Map<String, Object> taskSummary(String taskId) {
        return jdbc.queryForMap("""
                select status, total_count, success_count, failure_count,
                       running_count, updated_by, updated_at, finished_at
                from ingestion_task
                where id = ?
                """, taskId);
    }

    private List<Map<String, Object>> artifacts(long executionId) {
        return jdbc.queryForList("""
                select artifact_type, artifact_version, provenance,
                       producer_claim_version, object_key, content_sha256, created_at
                from ingestion_item_artifact
                where execution_id = ?
                order by artifact_type
                """, executionId);
    }

    private void insertProducedArtifact(
            long executionId,
            String artifactType,
            String objectKey,
            String sha256,
            long producerClaimVersion) {
        jdbc.update("""
                insert into ingestion_item_artifact (
                    execution_id, artifact_type, artifact_version, provenance,
                    producer_claim_version, object_key, content_sha256, created_at
                ) values (?, ?, 1, 'PRODUCED', ?, ?, ?, current_timestamp(6))
                """, executionId, artifactType, producerClaimVersion, objectKey, sha256);
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
