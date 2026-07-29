package com.anchr.core.ingestion.infrastructure.persistence;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class IngestionTwoTableMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_ingestion_two_table_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private SqlSession sqlSession;
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
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(false);
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

    private void insertPendingItem() {
        LocalDateTime now = LocalDateTime.now();
        IngestionTaskRecord task = new IngestionTaskRecord();
        task.setId("3001");
        task.setKbId("2001");
        task.setSourceType("UPLOAD");
        task.setDedupeStrategy("SKIP");
        task.setStatus("PENDING");
        task.setTotalCount(1);
        task.setSuccessCount(0);
        task.setFailureCount(0);
        task.setRunningCount(0);
        task.setCreatedBy("user-a");
        task.setUpdatedBy("user-a");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        mapper.insertTask(task);

        IngestionTaskItemRecord item = new IngestionTaskItemRecord();
        item.setId("4001");
        item.setTaskId("3001");
        item.setAssetId("5001");
        item.setTargetIndexGeneration(1L);
        item.setFileName("document.pdf");
        item.setStage("UPLOAD");
        item.setStatus("PENDING");
        item.setProgress(0);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        mapper.insertItem(item);
        sqlSession.commit();
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
