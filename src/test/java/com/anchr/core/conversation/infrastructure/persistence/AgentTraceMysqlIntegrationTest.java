package com.anchr.core.conversation.infrastructure.persistence;

import com.anchr.core.conversation.domain.model.AgentRun;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    @Test
    void insertRun_shouldBeIdempotentAndNeverOverwriteAnExistingSnapshot() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            AgentTraceRepositoryImpl repository = repository(session);
            AgentRun existing = repository.findRun("run-1").orElseThrow();
            existing.setStatus("CANCELLED");
            existing.setPromptTokens(31);
            existing.setCompletionTokens(9);
            existing.setFinishedAt(System.currentTimeMillis());
            assertThat(repository.transitionRun(existing, "WAITING_TASK")).isTrue();
            assertThat(repository.addRunTokenUsage("run-1", 31, 9)).isTrue();
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            AgentTraceRepositoryImpl repository = repository(session);
            AgentRun duplicate = run("run-1", "RUNNING");
            duplicate.setPromptTokens(999);
            duplicate.setCompletionTokens(999);
            repository.insertRun(duplicate);
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRun persisted = repository(session).findRun("run-1").orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo("CANCELLED");
            assertThat(persisted.getPromptTokens()).isEqualTo(31);
            assertThat(persisted.getCompletionTokens()).isEqualTo(9);
            assertThat(persisted.getFinishedAt()).isNotNull();
        }
    }

    @Test
    void runWrites_shouldRespectExpectedStatusAndKeepTokensIndependent() {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            AgentTraceRepositoryImpl repository = repository(session);
            AgentRun cancelled = repository.findRun("run-1").orElseThrow();
            cancelled.setStatus("CANCELLED");
            cancelled.setCurrentStep("TASK_CANCELLED");
            cancelled.setFinishedAt(System.currentTimeMillis());

            assertThat(repository.transitionRun(cancelled, "WAITING_TASK")).isTrue();
            assertThat(repository.transitionRun(run("run-1", "COMPLETED"), "WAITING_TASK")).isFalse();
            assertThat(repository.finishWorkflowRun(run("run-1", "AWAITING_TURN"))).isFalse();
            assertThat(repository.markTraditionalFallback("run-1", "traditional_rag_fallback")).isFalse();
            assertThat(repository.addRunTokenUsage("run-1", 13, 5)).isTrue();
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRun persisted = repository(session).findRun("run-1").orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo("CANCELLED");
            assertThat(persisted.getPromptTokens()).isEqualTo(13);
            assertThat(persisted.getCompletionTokens()).isEqualTo(5);
            assertThat(persisted.getCurrentStep()).isEqualTo("TASK_CANCELLED");
        }
    }

    @Test
    void cancellationAndTokenAddition_shouldNotLoseEitherUpdate() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> cancellation = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    AgentTraceRepositoryImpl repository = repository(session);
                    AgentRun run = repository.findRun("run-1").orElseThrow();
                    run.setStatus("CANCELLED");
                    run.setCurrentStep("TASK_CANCELLED");
                    run.setFinishedAt(System.currentTimeMillis());
                    boolean updated = repository.transitionRun(run, "WAITING_TASK");
                    session.commit();
                    return updated;
                }
            });
            Future<Boolean> tokens = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    boolean updated = repository(session).addRunTokenUsage("run-1", 17, 6);
                    session.commit();
                    return updated;
                }
            });
            start.countDown();

            assertThat(cancellation.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tokens.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRun persisted = repository(session).findRun("run-1").orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo("CANCELLED");
            assertThat(persisted.getPromptTokens()).isEqualTo(17);
            assertThat(persisted.getCompletionTokens()).isEqualTo(6);
        }
    }

    @Test
    void completionAndCancellation_shouldAllowExactlyOneWaitingTaskTransition() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> completion = executor.submit(() -> transitionAfter(start, "COMPLETED"));
            Future<Boolean> cancellation = executor.submit(() -> transitionAfter(start, "CANCELLED"));
            start.countDown();

            assertThat(List.of(
                    completion.get(5, TimeUnit.SECONDS),
                    cancellation.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(repository(session).findRun("run-1").orElseThrow().getStatus())
                    .isIn("COMPLETED", "CANCELLED");
        }
    }

    @Test
    void sessionLock_shouldSerializeRunCreationAndDeletionAtDefaultIsolation() throws Exception {
        assertRunCreationAndDeletionAreSerialized(Connection.TRANSACTION_REPEATABLE_READ);
    }

    @Test
    void sessionLock_shouldSerializeRunCreationAndDeletionAtReadCommitted() throws Exception {
        assertRunCreationAndDeletionAreSerialized(Connection.TRANSACTION_READ_COMMITTED);
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

    private boolean transitionAfter(CountDownLatch start, String targetStatus) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            AgentTraceRepositoryImpl repository = repository(session);
            AgentRun run = repository.findRun("run-1").orElseThrow();
            run.setStatus(targetStatus);
            run.setFinishedAt(System.currentTimeMillis());
            boolean updated = repository.transitionRun(run, "WAITING_TASK");
            session.commit();
            return updated;
        }
    }

    private void assertRunCreationAndDeletionAreSerialized(int isolation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch sessionLocked = new CountDownLatch(1);
        CountDownLatch releaseCreator = new CountDownLatch(1);
        CountDownLatch deletionAttempting = new CountDownLatch(1);
        try {
            Future<Boolean> creator = executor.submit(() -> {
                try (SqlSession session = sqlSessionFactory.openSession(false)) {
                    Connection connection = session.getConnection();
                    connection.setTransactionIsolation(isolation);
                    boolean active;
                    try (PreparedStatement statement = connection.prepareStatement("""
                            select 1 from conversation_session
                            where session_id = ? and deleted_at is null
                            for update
                            """)) {
                        statement.setString(1, "session-1");
                        try (ResultSet result = statement.executeQuery()) {
                            active = result.next();
                        }
                    }
                    sessionLocked.countDown();
                    assertThat(releaseCreator.await(5, TimeUnit.SECONDS)).isTrue();
                    if (active) repository(session).insertRun(run("run-race", "RUNNING"));
                    session.commit();
                    return active;
                }
            });
            assertThat(sessionLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Void> deleter = executor.submit(() -> {
                try (Connection connection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                    connection.setAutoCommit(false);
                    connection.setTransactionIsolation(isolation);
                    deletionAttempting.countDown();
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("""
                                update conversation_session set deleted_at = now(3)
                                where session_id = 'session-1' and deleted_at is null
                                """);
                        statement.executeUpdate("delete from agent_step where run_id = 'run-race'");
                        statement.executeUpdate("delete from agent_run where session_id = 'session-1'");
                    }
                    connection.commit();
                }
                return null;
            });
            assertThat(deletionAttempting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deleter.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseCreator.countDown();
            assertThat(creator.get(5, TimeUnit.SECONDS)).isTrue();
            deleter.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCreator.countDown();
            executor.shutdownNow();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(repository(session).findRun("run-race")).isEmpty();
        }
    }

    private AgentTraceRepositoryImpl repository(SqlSession session) {
        return new AgentTraceRepositoryImpl(session.getMapper(AgentTraceMapper.class));
    }

    private AgentRun run(String runId, String status) {
        AgentRun run = new AgentRun();
        run.setRunId(runId);
        run.setSessionId("session-1");
        run.setTurnId("turn-1");
        run.setStatus(status);
        run.setCurrentStep("MODEL_DECISION");
        run.setStartedAt(System.currentTimeMillis());
        return run;
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
