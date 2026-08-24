package com.anchr.core.ingestion.infrastructure.persistence;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskMapperXmlTest {

    private static final String NAMESPACE = IngestionTaskMapper.class.getName() + ".";

    @Test
    void mapperShouldExposeOnlyTwoTableBusinessStateOperations() throws Exception {
        Configuration configuration = loadConfiguration();

        assertThat(configuration.hasStatement(NAMESPACE + "claimPending")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "advanceRunningItem")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "completeRunningItem")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "failRunningItem")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "transitionClaim")).isFalse();
        assertThat(configuration.hasStatement(NAMESPACE + "updateClaimContext")).isFalse();
        assertThat(configuration.hasStatement(NAMESPACE + "renewClaim")).isFalse();
    }

    @Test
    void itemInsertShouldContainExactlyTheSixteenBusinessColumns() throws Exception {
        String sql = sql(loadConfiguration(), "insertItem", new IngestionTaskItemRecord());

        assertThat(sql)
                .contains("id, task_id, asset_id, target_index_generation, file_name, file_hash")
                .contains("stage, status, progress, dedupe_result, duplicate_asset_id")
                .contains("error_code, error_message, created_at, updated_at, finished_at")
                .doesNotContain("execution_")
                .doesNotContain("lease_")
                .doesNotContain("docling_")
                .doesNotContain("parse_attempt")
                .doesNotContain("parse_request_snapshot");
    }

    @Test
    void claimShouldOnlyMovePendingItemToRunningParse() throws Exception {
        String sql = sql(loadConfiguration(), "claimPending", Map.of("itemId", "item-1"));

        assertThat(sql)
                .contains("status = 'RUNNING'")
                .contains("stage = 'PARSE'")
                .contains("where id = ? and status = 'PENDING'")
                .doesNotContain("lease")
                .doesNotContain("claim_version");
    }

    @Test
    void retryShouldAssignNewGenerationAndClearOnlyOutcomeFields() throws Exception {
        String sql = sql(loadConfiguration(), "resetFailedItem", Map.of(
                "kbId", "kb-1", "taskId", "task-1", "itemId", "item-1",
                "nextTargetIndexGeneration", 8L, "updatedAt", LocalDateTime.now()));

        assertThat(sql)
                .contains("target_index_generation = ?")
                .contains("status = 'PENDING'")
                .contains("stage = 'UPLOAD'")
                .doesNotContain("parse_attempt")
                .doesNotContain("execution_epoch");
    }

    @Test
    void retryItemListShouldLockTheCurrentKbScopedFailedSetInStableOrder() throws Exception {
        String sql = sql(loadConfiguration(), "listRetryItemsForUpdate", Map.of(
                "kbId", "kb-1", "taskId", "task-1"));

        assertThat(sql)
                .contains("inner join ingestion_task it on it.id = iti.task_id")
                .contains("where it.kb_id = ? and iti.task_id = ?")
                .contains("iti.status = 'FAILED' and iti.asset_id is not null")
                .contains("order by iti.created_at asc, iti.id asc for update")
                .endsWith("for update");
    }

    @Test
    void itemListShouldUseOneSingleTableBatchQueryWithStableOrdering() throws Exception {
        String sql = sql(loadConfiguration(), "listItemsByTaskIds", Map.of(
                "taskIds", List.of("task-1", "task-2")));

        assertThat(sql)
                .contains("from ingestion_task_item iti")
                .contains("where iti.task_id in ( ? , ? )")
                .contains("order by iti.task_id asc, iti.created_at asc, iti.id asc")
                .doesNotContain("join ingestion_task");
    }

    private String sql(Configuration configuration, String statement, Object parameter) {
        BoundSql boundSql = configuration.getMappedStatement(NAMESPACE + statement)
                .getBoundSql(parameter);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private Configuration loadConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(IngestionTaskMapper.class);
        String resource = "mapper/ingestion/IngestionTaskMapper.xml";
        new XMLMapperBuilder(
                Resources.getResourceAsStream(resource), configuration, resource,
                configuration.getSqlFragments()).parse();
        return configuration;
    }
}
