package com.anchr.core.ingestion.application.impl;

import org.flywaydb.core.Flyway;
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
        jdbc.update("delete from ingestion_task_item");
        jdbc.update("delete from ingestion_task");
        jdbc.update("delete from asset");
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
        return jdbc.queryForObject("""
                select data_type, character_maximum_length, collation_name
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'ingestion_task'
                  and column_name = ?
                """, (resultSet, rowNum) -> List.of(
                resultSet.getString("data_type"),
                resultSet.getLong("character_maximum_length"),
                resultSet.getString("collation_name")), columnName);
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
