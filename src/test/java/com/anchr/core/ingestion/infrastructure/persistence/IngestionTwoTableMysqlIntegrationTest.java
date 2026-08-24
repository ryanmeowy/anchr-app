package com.anchr.core.ingestion.infrastructure.persistence;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class IngestionTwoTableMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_ingestion_two_table_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private SqlSession sqlSession;
    private SqlSessionFactory sqlSessionFactory;
    private IngestionTaskMapper mapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from ingestion_task_item");
            statement.executeUpdate("delete from ingestion_task");
        }
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(IngestionTaskMapper.class);
        String resource = "mapper/ingestion/IngestionTaskMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(false);
        mapper = sqlSession.getMapper(IngestionTaskMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) sqlSession.close();
    }

    @Test
    void migrationShouldCreateOnlyTwoIngestionTablesAndSixteenItemColumns() throws Exception {
        try (Connection connection = connection()) {
            assertThat(tableExists(connection, "ingestion_task")).isTrue();
            assertThat(tableExists(connection, "ingestion_task_item")).isTrue();
            assertThat(tableExists(connection, "ingestion_item_execution")).isFalse();
            assertThat(tableExists(connection, "ingestion_item_parse_attempt")).isFalse();
            assertThat(tableExists(connection, "ingestion_item_artifact")).isFalse();
            assertThat(columnCount(connection, "ingestion_task_item")).isEqualTo(16);
        }
    }

    @Test
    void pendingItemShouldBeClaimedAndAdvanceWithoutLeaseOrAttemptState() {
        insertPendingItem();

        assertThat(mapper.claimPending("4001")).isEqualTo(1);
        IngestionTaskItemRecord running = mapper.findRunningItem("4001").orElseThrow();
        assertThat(running.getStatus()).isEqualTo("RUNNING");
        assertThat(running.getStage()).isEqualTo("PARSE");
        assertThat(mapper.advanceRunningItem(
                "2001", "3001", "4001", "PARSE", "EMBED", 55,
                LocalDateTime.now())).isEqualTo(1);
        sqlSession.commit();

        IngestionTaskItemRecord stored = mapper.findItem("2001", "3001", "4001")
                .orElseThrow();
        assertThat(stored.getStage()).isEqualTo("EMBED");
        assertThat(stored.getProgress()).isEqualTo(55);
    }

    @Test
    void explicitRetryShouldUseNewGeneration() throws Exception {
        insertPendingItem();
        try (Statement statement = sqlSession.getConnection().createStatement()) {
            statement.executeUpdate("""
                    update ingestion_task_item
                    set status = 'FAILED', stage = 'INDEX', progress = 75,
                        target_index_generation = 7, finished_at = current_timestamp(6)
                    where id = 4001
                    """);
        }

        assertThat(mapper.resetFailedItem(
                "2001", "3001", "4001", 8L, LocalDateTime.now())).isEqualTo(1);
        sqlSession.commit();

        IngestionTaskItemRecord stored = mapper.findItem("2001", "3001", "4001")
                .orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("PENDING");
        assertThat(stored.getStage()).isEqualTo("UPLOAD");
        assertThat(stored.getTargetIndexGeneration()).isEqualTo(8L);
    }

    @Test
    void itemListShouldBatchAcrossTasksAndKeepStablePerTaskOrder() {
        LocalDateTime now = LocalDateTime.now();
        insertTask("3001", "2001", "user-a", now);
        insertItem("4002", "3001", "second.pdf", now);
        insertItem("4001", "3001", "first.pdf", now.minusSeconds(1));
        insertTask("3002", "2001", "user-b", now);
        insertItem("4003", "3002", "third.pdf", now.minusSeconds(2));
        sqlSession.commit();

        List<IngestionTaskItemRecord> items =
                mapper.listItemsByTaskIds(List.of("3002", "3001"));

        assertThat(items).extracting(IngestionTaskItemRecord::getId)
                .containsExactly("4001", "4002", "4003");
        assertThat(items).extracting(IngestionTaskItemRecord::getTaskId)
                .containsExactly("3001", "3001", "3002");
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getKbId()).isNull();
            assertThat(item.getTaskCreatedBy()).isNull();
            assertThat(item.getDedupeStrategy()).isNull();
        });
    }

    @Test
    void retryItemLockShouldSerializeBulkAndSingleRetryAtDefaultIsolation() throws Exception {
        assertBulkAndSingleRetryAreSerialized(null);
    }

    @Test
    void retryItemLockShouldSerializeBulkAndSingleRetryAtReadCommitted() throws Exception {
        assertBulkAndSingleRetryAreSerialized(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    void retryItemLockShouldSerializeTwoBulkRetriesAtDefaultIsolation() throws Exception {
        assertBulkRetriesAreSerialized(null);
    }

    @Test
    void retryItemLockShouldSerializeTwoBulkRetriesAtReadCommitted() throws Exception {
        assertBulkRetriesAreSerialized(Connection.TRANSACTION_READ_COMMITTED);
    }

    private void assertBulkAndSingleRetryAreSerialized(Integer isolation) throws Exception {
        insertFailedItem();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bulkLocked = new CountDownLatch(1);
        CountDownLatch releaseBulk = new CountDownLatch(1);
        CountDownLatch singleAttempting = new CountDownLatch(1);
        try {
            Future<Boolean> bulk = executor.submit(() -> lockAndResetRetryItems(
                    isolation, bulkLocked, releaseBulk));
            assertThat(bulkLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> single = executor.submit(() -> {
                singleAttempting.countDown();
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    setIsolation(session, isolation);
                    boolean found = session.getMapper(IngestionTaskMapper.class)
                            .findRetryItem("2001", "3001", "4001").isPresent();
                    session.commit();
                    return found;
                }
            });
            assertThat(singleAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> single.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseBulk.countDown();
            assertThat(bulk.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(single.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseBulk.countDown();
            executor.shutdownNow();
        }
        assertItemWasReset();
    }

    private void assertBulkRetriesAreSerialized(Integer isolation) throws Exception {
        insertFailedItem();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> lockAndResetRetryItems(
                    isolation, firstLocked, releaseFirst));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = executor.submit(() -> {
                secondAttempting.countDown();
                return lockAndResetRetryItems(isolation, null, null);
            });
            assertThat(secondAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
        assertItemWasReset();
    }

    private boolean lockAndResetRetryItems(Integer isolation,
                                           CountDownLatch locked,
                                           CountDownLatch release) throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            setIsolation(session, isolation);
            IngestionTaskMapper localMapper = session.getMapper(IngestionTaskMapper.class);
            List<IngestionTaskItemRecord> items =
                    localMapper.listRetryItemsForUpdate("2001", "3001");
            if (items.isEmpty()) {
                session.commit();
                return false;
            }
            if (locked != null) locked.countDown();
            if (release != null && !release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release retry item lock.");
            }
            boolean reset = localMapper.resetFailedItem(
                    "2001", "3001", items.getFirst().getId(), 8L,
                    LocalDateTime.now()) == 1;
            session.commit();
            return reset;
        }
    }

    private void setIsolation(SqlSession session, Integer isolation) throws Exception {
        if (isolation != null) session.getConnection().setTransactionIsolation(isolation);
    }

    private void insertFailedItem() throws Exception {
        insertPendingItem();
        try (Statement statement = sqlSession.getConnection().createStatement()) {
            statement.executeUpdate("""
                    update ingestion_task_item
                    set status = 'FAILED', stage = 'INDEX', progress = 75
                    where id = 4001
                    """);
        }
        sqlSession.commit();
    }

    private void assertItemWasReset() {
        IngestionTaskItemRecord item = mapper.findItem("2001", "3001", "4001")
                .orElseThrow();
        assertThat(item.getStatus()).isEqualTo("PENDING");
        assertThat(item.getStage()).isEqualTo("UPLOAD");
    }

    private void insertPendingItem() {
        LocalDateTime now = LocalDateTime.now();
        insertTask("3001", "2001", "user-a", now);
        insertItem("4001", "3001", "document.pdf", now);
        sqlSession.commit();
    }

    private void insertTask(String taskId, String kbId, String createdBy, LocalDateTime now) {
        IngestionTaskRecord task = new IngestionTaskRecord();
        task.setId(taskId);
        task.setKbId(kbId);
        task.setSourceType("UPLOAD");
        task.setDedupeStrategy("SKIP");
        task.setStatus("PENDING");
        task.setTotalCount(1);
        task.setSuccessCount(0);
        task.setFailureCount(0);
        task.setRunningCount(0);
        task.setCreatedBy(createdBy);
        task.setUpdatedBy(createdBy);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        mapper.insertTask(task);
    }

    private void insertItem(String itemId, String taskId, String fileName, LocalDateTime now) {
        IngestionTaskItemRecord item = new IngestionTaskItemRecord();
        item.setId(itemId);
        item.setTaskId(taskId);
        item.setAssetId("5001");
        item.setTargetIndexGeneration(1L);
        item.setFileName(fileName);
        item.setStage("UPLOAD");
        item.setStatus("PENDING");
        item.setProgress(0);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        mapper.insertItem(item);
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (ResultSet resultSet = connection.getMetaData().getTables(
                connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private static int columnCount(Connection connection, String table) throws Exception {
        try (ResultSet resultSet = connection.getMetaData().getColumns(
                connection.getCatalog(), null, table, null)) {
            int count = 0;
            while (resultSet.next()) count++;
            return count;
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
