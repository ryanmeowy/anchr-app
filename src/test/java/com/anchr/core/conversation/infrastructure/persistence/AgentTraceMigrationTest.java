package com.anchr.core.conversation.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AgentTraceMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
            .withDatabaseName("anchr")
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

    @Test
    void migration_shouldPersistOrderedStepsAndLinkTurnToRun() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.createStatement().executeUpdate("""
                    insert into conversation_session
                      (session_id, user_id, status, kb_scope, asset_scope, created_at, updated_at)
                    values ('session-1', 'single_user', 'ACTIVE', json_array(), json_array(), now(3), now(3))
                    """);
            connection.createStatement().executeUpdate("""
                    insert into agent_run
                      (run_id, session_id, turn_id, status, current_step, started_at)
                    values ('run-1', 'session-1', 'turn-1', 'RUNNING', 'MODEL_DECISION', now(3))
                    """);
            connection.createStatement().executeUpdate("""
                    insert into agent_step
                      (step_id, run_id, step_order, step_type, attempt, status, input_summary, output_summary, created_at)
                    values
                      ('step-2', 'run-1', 2, 'TOOL_RESULT', 1, 'COMPLETED', json_object('tool','search_knowledge'), json_object('count',2), now(3)),
                      ('step-1', 'run-1', 1, 'MODEL_DECISION', 1, 'COMPLETED', json_object(), json_object('toolCalls',1), now(3))
                    """);
            connection.createStatement().executeUpdate("""
                    insert into conversation_turn
                      (turn_id, session_id, query, answer, agent_run_id, created_at)
                    values ('turn-1', 'session-1', 'q', 'a', 'run-1', now(3))
                    """);

            try (ResultSet steps = connection.createStatement().executeQuery(
                    "select step_type from agent_step where run_id='run-1' order by step_order")) {
                assertThat(steps.next()).isTrue();
                assertThat(steps.getString(1)).isEqualTo("MODEL_DECISION");
                assertThat(steps.next()).isTrue();
                assertThat(steps.getString(1)).isEqualTo("TOOL_RESULT");
            }
            try (ResultSet turn = connection.createStatement().executeQuery(
                    "select agent_run_id from conversation_turn where turn_id='turn-1'")) {
                assertThat(turn.next()).isTrue();
                assertThat(turn.getString("agent_run_id")).isEqualTo("run-1");
            }
        }
    }

    @Test
    void deletingRun_shouldNotRelyOnDatabaseCascade() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.createStatement().executeUpdate("""
                    insert into agent_run
                      (run_id, session_id, status, current_step, started_at)
                    values ('run-cascade', 'session-1', 'FAILED', 'MODEL_DECISION', now(3))
                    """);
            connection.createStatement().executeUpdate("""
                    insert into agent_step
                      (step_id, run_id, step_order, step_type, attempt, status, created_at)
                    values ('step-cascade', 'run-cascade', 1, 'MODEL_DECISION', 1, 'FAILED', now(3))
                    """);

            connection.createStatement().executeUpdate("delete from agent_run where run_id='run-cascade'");

            try (ResultSet result = connection.createStatement().executeQuery(
                    "select count(*) from agent_step where run_id='run-cascade'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            connection.createStatement().executeUpdate(
                    "delete from agent_step where run_id='run-cascade'");
        }
    }

    @Test
    void migration_shouldRemoveWorkflowVersionColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             ResultSet turnColumn = connection.getMetaData().getColumns(
                     MYSQL.getDatabaseName(), null, "conversation_turn", "workflow_version");
             ResultSet runColumn = connection.getMetaData().getColumns(
                     MYSQL.getDatabaseName(), null, "agent_run", "workflow_version")) {
            assertThat(turnColumn.next()).isFalse();
            assertThat(runColumn.next()).isFalse();
        }
    }
}
