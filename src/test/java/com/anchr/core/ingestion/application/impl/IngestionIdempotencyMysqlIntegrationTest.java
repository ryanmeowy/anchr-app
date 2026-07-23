package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.infrastructure.persistence.IngestionTaskItemRecord;
import com.anchr.core.ingestion.infrastructure.persistence.IngestionTaskMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class IngestionIdempotencyMysqlIntegrationTest {

    private static final String REQUEST_HASH = "v1:" + "a".repeat(64);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_ingestion_idempotency_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private JdbcTemplate jdbc;
    private IngestionCreateTransactionRunner transactionRunner;
    private SqlSession sqlSession;
    private IngestionTaskMapper ingestionTaskMapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionRunner = new IngestionCreateTransactionRunner(new JdbcTransactionManager(dataSource));
        Configuration configuration = new Configuration(new Environment(
                "mysql-test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(IngestionTaskMapper.class);
        try {
            String resource = "mapper/ingestion/IngestionTaskMapper.xml";
            XMLMapperBuilder builder = new XMLMapperBuilder(
                    Resources.getResourceAsStream(resource),
                    configuration,
                    resource,
                    configuration.getSqlFragments());
            builder.parse();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load ingestion mapper", e);
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(true);
        ingestionTaskMapper = sqlSession.getMapper(IngestionTaskMapper.class);
        jdbc.update("delete from ingestion_task_item");
        jdbc.update("delete from ingestion_task");
        jdbc.update("delete from asset");
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void migration_shouldInstallVersionedHashColumnsAndBinaryUniqueKey() {
        assertThat(column("client_request_id"))
                .containsExactly("varchar", 128L, "utf8mb4_bin");
        assertThat(column("request_hash"))
                .containsExactly("varchar", 80L, "ascii_bin");

        List<String> indexColumns = jdbc.queryForList("""
                select column_name
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'ingestion_task'
                  and index_name = 'uk_ingestion_task_creator_request'
                  and non_unique = 0
                order by seq_in_index
                """, String.class);
        assertThat(indexColumns).containsExactly("created_by", "client_request_id");
    }

    @Test
    @Timeout(20)
    void concurrentCreate_shouldRollbackLoserAssetThenReadWinnerInNewTransaction() throws Exception {
        CountDownLatch bothAssetsWritten = new CountDownLatch(2);
        AtomicInteger duplicateCount = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(createAttempt(
                    "1001", "2001", bothAssetsWritten, duplicateCount));
            Future<String> second = executor.submit(createAttempt(
                    "1002", "2002", bothAssetsWritten, duplicateCount));

            String firstResult = first.get(15, TimeUnit.SECONDS);
            String secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(firstResult).isEqualTo(secondResult);
            assertThat(duplicateCount).hasValue(1);
            assertThat(count("ingestion_task")).isEqualTo(1);
            assertThat(count("asset")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select request_hash from ingestion_task where id = ?", String.class, firstResult))
                    .isEqualTo(REQUEST_HASH);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void parseAttempt_shouldPersistIdentityIncrementOnExplicitRetryAndFenceOldJob() {
        assertThat(column("ingestion_task_item", "docling_request_id"))
                .containsExactly("varchar", 200L, "utf8mb4_bin");
        assertThat(column("ingestion_task_item", "docling_job_id"))
                .containsExactly("varchar", 64L, "ascii_bin");
        assertThat(column("ingestion_task_item", "source_revision"))
                .containsExactly("varchar", 80L, "ascii_bin");

        insertTask("3001");
        String revision = "v1:" + "b".repeat(64);
        jdbc.update("""
                insert into ingestion_task_item (
                    id, task_id, kb_id, asset_id, file_name, file_hash, source_url,
                    parse_attempt, docling_request_id, docling_job_id, source_revision,
                    stage, status, progress, created_at, updated_at
                ) values (
                    4001, 3001, 1, 5001, 'sample.pdf', 'hash-a', null,
                    1, '3001:4001:1', 'job-old', ?,
                    'PARSE', 'FAILED', 20, now(), now()
                )
                """, revision);

        assertThat(ingestionTaskMapper.resetFailedItem(
                "1", "3001", "4001",
                1, 2, "3001:4001:2", LocalDateTime.now())).isEqualTo(1);
        IngestionTaskItemRecord retried = ingestionTaskMapper.findItem("1", "3001", "4001")
                .orElseThrow();
        assertThat(retried.getParseAttempt()).isEqualTo(2);
        assertThat(retried.getDoclingRequestId()).isEqualTo("3001:4001:2");
        assertThat(retried.getDoclingJobId()).isNull();
        assertThat(retried.getSourceRevision()).isEqualTo(revision);
        jdbc.update("""
                update ingestion_task_item
                set status = 'FAILED', docling_job_id = 'job-current'
                where id = 4001
                """);
        assertThat(ingestionTaskMapper.resetFailedItem(
                "1", "3001", "4001",
                1, 2, "3001:4001:2", LocalDateTime.now())).isZero();
        IngestionTaskItemRecord afterStaleRetry = ingestionTaskMapper.findItem("1", "3001", "4001")
                .orElseThrow();
        assertThat(afterStaleRetry.getParseAttempt()).isEqualTo(2);
        assertThat(afterStaleRetry.getDoclingRequestId()).isEqualTo("3001:4001:2");
        assertThat(afterStaleRetry.getDoclingJobId()).isEqualTo("job-current");

        jdbc.update("""
                update ingestion_task_item
                set status = 'RUNNING',
                    stage_attempt = 1,
                    lease_token = 'lease-current',
                    lease_until = timestampadd(second, 60, current_timestamp(6))
                where id = 4001
                """);
        assertThat(ingestionTaskMapper.updateClaimContext(parseContext(
                "3001:4001:2", "job-wrong-revision",
                "v1:" + "c".repeat(64)))).isZero();
        assertThat(ingestionTaskMapper.updateClaimContext(parseContext(
                "3001:4001:1", "job-stale", revision))).isZero();
        assertThat(ingestionTaskMapper.updateClaimContext(parseContext(
                "3001:4001:2", "job-new", revision))).isEqualTo(1);
        assertThat(ingestionTaskMapper.findItem("1", "3001", "4001")
                .orElseThrow().getDoclingJobId()).isEqualTo("job-new");
    }

    private IngestionClaimContext parseContext(String requestId,
                                               String jobId,
                                               String sourceRevision) {
        return IngestionClaimContext.builder()
                .itemId("4001")
                .executionEpoch(2L)
                .expectedExecutionStage(IngestionExecutionStage.PARSE_SUBMIT)
                .stageAttempt(1)
                .leaseToken("lease-current")
                .parseAttempt(2)
                .doclingRequestId(requestId)
                .doclingJobId(jobId)
                .sourceRevision(sourceRevision)
                .parseRequestSnapshot("""
                        {"artifactVersion":1,"contractVersion":2,"fileName":"sample.pdf"}
                        """.trim())
                .build();
    }

    private Callable<String> createAttempt(String taskId, String assetId,
                                           CountDownLatch bothAssetsWritten,
                                           AtomicInteger duplicateCount) {
        return () -> {
            try {
                return transactionRunner.write(() -> {
                    insertAsset(assetId);
                    bothAssetsWritten.countDown();
                    await(bothAssetsWritten);
                    insertTask(taskId);
                    return taskId;
                });
            } catch (DuplicateKeyException duplicate) {
                assertThat(messageChain(duplicate))
                        .contains("uk_ingestion_task_creator_request");
                duplicateCount.incrementAndGet();
                return transactionRunner.read(() -> jdbc.queryForObject("""
                        select id
                        from ingestion_task
                        where created_by = 'user-a'
                          and client_request_id = 'request-race'
                        """, String.class));
            }
        };
    }

    private void insertAsset(String assetId) {
        jdbc.update("""
                insert into asset (
                    id, kb_id, file_name, file_type, parse_status, index_status,
                    segment_count, indexed_segment_count, created_by, updated_by, created_at, updated_at
                ) values (?, 1, ?, 'PDF', 'PENDING', 'PENDING', 0, 0, 'user-a', 'user-a', now(), now())
                """, assetId, assetId + ".pdf");
    }

    private void insertTask(String taskId) {
        jdbc.update("""
                insert into ingestion_task (
                    id, kb_id, source_type, client_request_id, request_hash, status,
                    total_count, success_count, failure_count, running_count,
                    created_by, updated_by, created_at, updated_at
                ) values (?, 1, 'UPLOAD', 'request-race', ?, 'PENDING', 1, 0, 0, 0,
                          'user-a', 'user-a', now(), now())
                """, taskId, REQUEST_HASH);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent ingestion attempts did not rendezvous.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent ingestion attempt interrupted.", interrupted);
        }
    }

    private List<Object> column(String columnName) {
        return column("ingestion_task", columnName);
    }

    private List<Object> column(String tableName, String columnName) {
        return jdbc.queryForObject("""
                select data_type, character_maximum_length, collation_name
                from information_schema.columns
                where table_schema = database()
                  and table_name = ?
                  and column_name = ?
                """, (resultSet, rowNum) -> List.of(
                resultSet.getString("data_type"),
                resultSet.getLong("character_maximum_length"),
                resultSet.getString("collation_name")), tableName, columnName);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private String messageChain(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }
}
