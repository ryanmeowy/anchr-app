package com.anchr.core.kb.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OutboxMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_test")
            .withUsername("anchr")
            .withPassword("anchr");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from outbox_event");
            statement.executeUpdate("delete from asset");
        }
    }

    @Test
    void migrationShouldCreateOutboxColumnsAndIndexes() throws Exception {
        try (Connection connection = connection()) {
            assertThat(columnExists(connection, "outbox_event", "next_retry_at")).isTrue();
            assertThat(columnExists(connection, "outbox_event", "lock_token")).isTrue();
            assertThat(indexExists(connection, "outbox_event", "idx_outbox_poll")).isTrue();
            assertThat(indexExists(connection, "outbox_event", "idx_outbox_locked")).isTrue();
            assertThat(columnExists(connection, "asset", "active_index_generation")).isTrue();
            assertThat(columnExists(connection, "asset_index_change", "revision")).isTrue();
            assertThat(indexExists(
                    connection,
                    "asset_index_change",
                    "idx_asset_index_change_kb_revision")).isTrue();
            assertThat(columnExists(
                    connection, "embedding_profile_deployment", "serving_fingerprint")).isTrue();
            assertThat(columnExists(
                    connection, "embedding_profile_deployment", "rebuild_phase")).isTrue();
            assertThat(columnExists(
                    connection, "physical_index_profile", "max_applied_revision")).isTrue();
        assertThat(columnExists(
                connection, "embedding_index_write_lease", "expires_at")).isTrue();
        assertThat(columnExists(
                connection, "physical_index_profile", "config_id")).isTrue();
        }
    }

    @Test
    void skipLockedShouldAllowWorkersToClaimDifferentEvents() throws Exception {
        insertOutboxEvent("1001");
        insertOutboxEvent("1002");

        try (Connection first = connection(); Connection second = connection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            long firstId = claimOne(first);
            long secondId = claimOne(second);

            assertThat(firstId).isNotEqualTo(secondId);
            first.rollback();
            second.rollback();
        }
    }

    @Test
    void assetRowLockShouldSerializeIndexFinalizationAndDelete() throws Exception {
        insertAsset();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection indexFinalization = connection()) {
            indexFinalization.setAutoCommit(false);
            try (PreparedStatement statement = indexFinalization.prepareStatement(
                    "select id from asset where id = 1001 and kb_id = 2001 for update")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                }
            }

            Future<Integer> delete = executor.submit(() -> {
                try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                        "update asset set deleted_at = now(), updated_at = now() "
                                + "where id = 1001 and kb_id = 2001 and deleted_at is null")) {
                    return statement.executeUpdate();
                }
            });

            TimeUnit.MILLISECONDS.sleep(200);
            assertThat(delete.isDone()).isFalse();
            indexFinalization.commit();
            assertThat(delete.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private long claimOne(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select id
                from outbox_event
                where (status = 'PENDING' and (next_retry_at is null or next_retry_at <= ?))
                   or (status = 'PROCESSING' and locked_at <= ?)
                order by id asc
                limit 1
                for update skip locked
                """)) {
            statement.setObject(1, LocalDateTime.now());
            statement.setObject(2, LocalDateTime.now().minusMinutes(5));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private void insertOutboxEvent(String assetId) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                insert into outbox_event (
                    event_type, aggregate_type, aggregate_id, payload, status,
                    retry_count, created_by, created_at, updated_at
                ) values (
                    'DELETE_ASSET', 'ASSET', ?,
                    json_object('kbId', '2001', 'assetId', ?),
                    'PENDING', 0, 'test', now(), now()
                )
                """)) {
            statement.setString(1, assetId);
            statement.setString(2, assetId);
            statement.executeUpdate();
        }
    }

    private void insertAsset() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into asset (
                        id, kb_id, file_name, file_type, parse_status, index_status,
                        created_at, updated_at
                    ) values (
                        1001, 2001, 'test.pdf', 'PDF', 'SUCCESS', 'RUNNING', now(), now()
                    )
                    """);
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet resultSet = connection.getMetaData().getColumns(
                connection.getCatalog(), null, table, column)) {
            return resultSet.next();
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws Exception {
        try (ResultSet resultSet = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, table, false, false)) {
            while (resultSet.next()) {
                if (index.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
