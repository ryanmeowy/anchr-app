package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceMapperXmlTest {

    private static final String NAMESPACE = AgentTraceMapper.class.getName() + ".";

    @Test
    void cleanupStatementsShouldFindRunsAndDeleteStepsByRunIds() throws Exception {
        Configuration configuration = loadConfiguration();

        String findOlderTerminalRunsSql = sql(
                configuration,
                "findOlderTerminalRunIds",
                Map.of("sessionId", "session-1", "currentTurnId", "turn-current"));
        String findRunsSql = sql(
                configuration,
                "findRunIdsBySessionId",
                Map.of("sessionId", "session-1"));
        String deleteStepsSql = sql(
                configuration,
                "deleteStepsByRunIds",
                Map.of("runIds", List.of("run-1", "run-2")));

        assertThat(findOlderTerminalRunsSql)
                .contains("from agent_run r join conversation_turn previous_turn")
                .contains("join conversation_turn current_turn")
                .contains("current_turn.turn_id = ?")
                .contains("current_turn.deleted_at is null")
                .contains("r.session_id = ?")
                .contains("r.status in ('COMPLETED', 'CANCELLED', 'FAILED', 'DEGRADED', 'FALLBACK')")
                .contains("previous_turn.created_at < current_turn.created_at")
                .contains("previous_turn.created_at = current_turn.created_at")
                .contains("previous_turn.turn_id < current_turn.turn_id");
        assertThat(findRunsSql)
                .isEqualTo("select run_id from agent_run where session_id = ? order by run_id asc");
        assertThat(deleteStepsSql)
                .isEqualTo("delete from agent_step where run_id in ( ? , ? )");
    }

    private String sql(Configuration configuration, String statement, Object parameter) {
        BoundSql boundSql = configuration.getMappedStatement(NAMESPACE + statement)
                .getBoundSql(parameter);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private Configuration loadConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AgentTraceMapper.class);
        String resource = "mapper/conversation/AgentTraceMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
