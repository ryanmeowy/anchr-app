package com.anchr.core.conversation.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.conversation.domain.model.ConversationSession;
import com.anchr.core.conversation.domain.model.ConversationTurn;
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
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ConversationMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_conversation_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private SqlSession sqlSession;
    private ConversationRepositoryImpl repository;

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
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from conversation_turn");
            statement.executeUpdate("delete from conversation_session");
        }

        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ConversationMapper.class);
        String resource = "mapper/conversation/ConversationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession(true);
        repository = new ConversationRepositoryImpl(sqlSession.getMapper(ConversationMapper.class), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void shouldPersistUpsertOrderLimitAndCascadeDelete() {
        ConversationSession older = session("cvs_older", 1_000L, List.of("kb_1"));
        ConversationSession newer = session("cvs_newer", 2_000L, List.of("kb_2"));
        repository.saveSession(older);
        repository.saveSession(newer);

        newer.setTitle("updated title");
        newer.setUpdatedAt(3_000L);
        repository.saveSession(newer);

        ConversationTurn first = turn("turn_1", newer.getSessionId(), 4_000L, "[]");
        ConversationTurn second = turn("turn_2", newer.getSessionId(), 5_000L, "[{\"segmentId\":\"seg_2\"}]");
        repository.saveTurn(first);
        repository.saveTurn(second);

        assertThat(repository.findSession(newer.getSessionId())).get()
                .extracting(ConversationSession::getTitle).isEqualTo("updated title");
        assertThat(repository.findSession(newer.getSessionId()).orElseThrow().getExpiresAt()).isNull();
        assertThat(repository.findRecentSessions("single_user", 1))
                .extracting(ConversationSession::getSessionId).containsExactly("cvs_newer");
        assertThat(repository.findRecentTurns(newer.getSessionId(), 1))
                .extracting(ConversationTurn::getTurnId).containsExactly("turn_2");
        assertThat(repository.findTurn("wrong_session", "turn_2")).isEmpty();
        assertThat(repository.findTurn(newer.getSessionId(), "turn_2")).get()
                .extracting(ConversationTurn::getCitationsJson)
                .isEqualTo("[{\"segmentId\": \"seg_2\"}]");

        repository.deleteSession(newer.getSessionId());

        assertThat(repository.findSession(newer.getSessionId())).isEmpty();
        assertThat(repository.findRecentTurns(newer.getSessionId(), 10)).isEmpty();
        assertThat(countRows("conversation_session", "session_id", newer.getSessionId())).isEqualTo(1);
        assertThat(countRows("conversation_turn", "session_id", newer.getSessionId())).isEqualTo(2);
        assertThat(countDeletedRows("conversation_session", "session_id", newer.getSessionId())).isEqualTo(1);
        assertThat(countDeletedRows("conversation_turn", "session_id", newer.getSessionId())).isEqualTo(2);
    }

    @Test
    void deletedSession_shouldNotBeRevivedByAStaleSave() {
        ConversationSession session = session("cvs_deleted", 1_000L, List.of("kb_1"));
        repository.saveSession(session);
        assertThat(repository.lockActiveSession(session.getSessionId())).isTrue();

        repository.deleteSession(session.getSessionId());
        session.setTitle("stale worker update");
        session.setUpdatedAt(2_000L);
        repository.saveSession(session);

        assertThat(repository.lockActiveSession(session.getSessionId())).isFalse();
        assertThat(repository.findSession(session.getSessionId())).isEmpty();
        assertThat(countDeletedRows("conversation_session", "session_id", session.getSessionId())).isEqualTo(1);
    }

    private ConversationSession session(String sessionId, long timestamp, List<String> kbScope) {
        ConversationSession session = ConversationSession.createActive(sessionId, "single_user", null, timestamp);
        session.setUpdatedAt(timestamp);
        session.setKbScope(kbScope);
        session.setAssetScope(List.of());
        return session;
    }

    private ConversationTurn turn(String turnId, String sessionId, long timestamp, String citations) {
        ConversationTurn turn = new ConversationTurn();
        turn.setTurnId(turnId);
        turn.setSessionId(sessionId);
        turn.setQuery("query " + turnId);
        turn.setRewrittenQuery("rewritten " + turnId);
        turn.setAnswer("answer " + turnId);
        turn.setKbScopeJson("[]");
        turn.setAssetScopeJson("[]");
        turn.setAnswerMode("STRICT");
        turn.setAnswerStatus("ANSWERED");
        turn.setCitationsJson(citations);
        turn.setResultCardsJson("[]");
        turn.setRetrievalTraceJson("{}");
        turn.setCreatedAt(timestamp);
        return turn;
    }

    private int countRows(String table, String column, String value) {
        return count(table, column, value, false);
    }

    private int countDeletedRows(String table, String column, String value) {
        return count(table, column, value, true);
    }

    private int count(String table, String column, String value, boolean deletedOnly) {
        String sql = "select count(*) from " + table + " where " + column + " = ?"
                + (deletedOnly ? " and deleted_at is not null" : "");
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
