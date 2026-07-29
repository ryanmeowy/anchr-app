package com.anchr.core.activity.infrastructure.persistence;

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
class ActivityEventMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_activity_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private SqlSession sqlSession;
    private ActivityEventMapper mapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from activity_event");
        }

        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ActivityEventMapper.class);
        String resource = "mapper/activity/ActivityEventMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = sqlSession.getMapper(ActivityEventMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void deleteBySessionId_shouldDeleteQuestionAndCitationEventsOnlyForTargetSession() throws Exception {
        insert("1001", "QUESTION_ASKED", "{\"sessionId\":\"session-target\"}");
        insert("1002", "CITATION_OPENED", "{\"sessionId\":\"session-target\",\"assetId\":\"asset-1\"}");
        insert("1003", "CITATION_OPENED", "{\"sessionId\":\"session-other\",\"assetId\":\"asset-2\"}");
        insert("1004", "SEARCH_EXECUTED", "{\"sessionId\":\"session-target\"}");

        assertThat(mapper.deleteBySessionId("session-target")).isEqualTo(2);

        assertThat(countEvents("session-target", "CITATION_OPENED")).isZero();
        assertThat(countEvents("session-target", "QUESTION_ASKED")).isZero();
        assertThat(countEvents("session-other", "CITATION_OPENED")).isEqualTo(1);
        assertThat(countEvents("session-target", "SEARCH_EXECUTED")).isEqualTo(1);
    }

    private void insert(String id, String eventType, String payload) {
        ActivityEventRecord record = new ActivityEventRecord();
        record.setId(id);
        record.setUserId("single_user");
        record.setEventType(eventType);
        record.setResourceType("TEST");
        record.setPayload(payload);
        record.setCreatedAt(LocalDateTime.now());
        mapper.insert(record);
    }

    private int countEvents(String sessionId, String eventType) throws Exception {
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
                select count(*)
                from activity_event
                where event_type = ?
                  and JSON_UNQUOTE(JSON_EXTRACT(payload, '$.sessionId')) = ?
                """)) {
            statement.setString(1, eventType);
            statement.setString(2, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
