package com.anchr.core.conversation.infrastructure.persistence;

import com.anchr.core.conversation.domain.model.AgentStep;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AgentTraceMysqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("anchr_agent_trace_test")
            .withUsername("anchr")
            .withPassword("anchr");

    private SqlSessionFactory sqlSessionFactory;

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
            statement.executeUpdate("delete from agent_step");
            statement.executeUpdate("delete from agent_run");
            statement.executeUpdate("delete from conversation_session");
            statement.executeUpdate("""
                    insert into conversation_session
                      (session_id, user_id, status, kb_scope, asset_scope, created_at, updated_at)
                    values ('session-1', 'single_user', 'ACTIVE', json_array(), json_array(), now(3), now(3))
                    """);
            statement.executeUpdate("""
                    insert into agent_run
                      (run_id, session_id, turn_id, status, current_step, started_at)
                    values ('run-1', 'session-1', 'turn-1', 'WAITING_TASK', 'TOOL_RESULT', now(3))
                    """);
            statement.executeUpdate("""
                    insert into agent_step
                      (step_id, run_id, step_order, step_type, attempt, status, decision_code, created_at)
                    values
                      ('model-1', 'run-1', 1, 'MODEL_DECISION', 1, 'COMPLETED', 'TOOL_CALLS', now(3)),
                      ('tool-2', 'run-1', 2, 'TOOL_RESULT', 1, 'COMPLETED', 'SUCCESS', now(3))
                    """);
        }

        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(AgentTraceMapper.class);
        String resource = "mapper/conversation/AgentTraceMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void runLock_shouldSerializeConcurrentStepAllocation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> allocate(
                    "task-1", "READING", firstLocked, releaseFirst));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> second = executor.submit(() -> {
                secondAttempting.countDown();
                return allocate("task-1", "MAP_SUMMARY", null, null);
            });
            assertThat(secondAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(4);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentTraceRepositoryImpl repository = repository(session);
            assertThat(repository.findSteps("run-1"))
                    .extracting(AgentStep::getStepOrder)
                    .containsExactly(1, 2, 3, 4);
        }
    }

    private int allocate(String taskId, String stage,
                         CountDownLatch locked, CountDownLatch release) throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            AgentTraceRepositoryImpl repository = repository(session);
            assertThat(repository.lockRun("run-1")).isTrue();
            if (locked != null) locked.countDown();
            if (release != null) assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            List<AgentStep> steps = repository.findSteps("run-1");
            int order = steps.stream().mapToInt(AgentStep::getStepOrder).max().orElse(0) + 1;
            AgentStep step = taskStage(taskId, stage, order);
            repository.saveStep(step);
            session.commit();
            return order;
        }
    }

    private AgentTraceRepositoryImpl repository(SqlSession session) {
        return new AgentTraceRepositoryImpl(session.getMapper(AgentTraceMapper.class));
    }

    private AgentStep taskStage(String taskId, String stage, int order) {
        AgentStep step = new AgentStep();
        step.setStepId(UUID.nameUUIDFromBytes(
                ("run-1:" + taskId + ":1:" + stage).getBytes(StandardCharsets.UTF_8)).toString());
        step.setRunId("run-1");
        step.setStepOrder(order);
        step.setStepType("TASK_STAGE");
        step.setAttempt(1);
        step.setStatus("RUNNING");
        step.setDecisionCode(stage);
        step.setInputSummaryJson("{}");
        step.setOutputSummaryJson("{\"progress\":5}");
        step.setCreatedAt(System.currentTimeMillis());
        return step;
    }
}
