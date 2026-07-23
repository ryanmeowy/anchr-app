package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskMapperXmlTest {

    @Test
    void mapperXml_shouldExposeOnlyFencedExecutionStatements() throws Exception {
        Configuration configuration = loadConfiguration();

        String namespace = IngestionTaskMapper.class.getName() + ".";
        assertThat(configuration.hasStatement(namespace + "resetFailedItem")).isTrue();
        assertThat(configuration.hasStatement(namespace + "listClaimableItemIds")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectClaimableItemForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace + "claimItem")).isTrue();
        assertThat(configuration.hasStatement(namespace + "renewClaim")).isTrue();
        assertThat(configuration.hasStatement(namespace + "transitionClaim")).isTrue();
        assertThat(configuration.hasStatement(namespace + "findCurrentClaimForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace + "prepareParseAttempt")).isFalse();
        assertThat(configuration.hasStatement(namespace + "recordDoclingJob")).isFalse();
        assertThat(configuration.hasStatement(namespace + "markItemRunning")).isFalse();
        assertThat(configuration.hasStatement(namespace + "markItemSuccess")).isFalse();
        assertThat(configuration.hasStatement(namespace + "markItemFailed")).isFalse();
        assertThat(configuration.hasStatement(namespace + "resetFailedItems")).isFalse();
    }

    @Test
    void updateClaimContext_shouldFenceStableParseIdentityAndSnapshot() throws Exception {
        Configuration configuration = loadConfiguration();
        String statement = IngestionTaskMapper.class.getName() + ".updateClaimContext";
        BoundSql boundSql = configuration.getMappedStatement(statement).getBoundSql(Map.of(
                "itemId", "item-1",
                "executionEpoch", 2L,
                "expectedExecutionStage", IngestionExecutionStage.PARSE_SUBMIT,
                "stageAttempt", 1,
                "leaseToken", "lease-1",
                "parseAttempt", 3,
                "doclingRequestId", "task-1:item-1:3",
                "doclingJobId", "job-1",
                "sourceRevision", "v1:revision",
                "parseRequestSnapshot", "{\"fileName\":\"sample.pdf\"}"));

        assertThat(boundSql.getSql())
                .contains("parse_attempt = ?")
                .contains("docling_request_id is null or docling_request_id = ?")
                .contains("source_revision is null or source_revision = ?")
                .contains("parse_request_snapshot is null")
                .contains("parse_request_snapshot = cast(? as json)");
    }

    @Test
    void resetFailedItem_shouldBindExplicitNextIdentityAndExpectedAttempt() throws Exception {
        Configuration configuration = loadConfiguration();
        String statement = IngestionTaskMapper.class.getName() + ".resetFailedItem";
        BoundSql boundSql = configuration.getMappedStatement(statement).getBoundSql(Map.of(
                "kbId", "kb-1",
                "taskId", "task-1",
                "itemId", "item-1",
                "expectedParseAttempt", 7,
                "nextParseAttempt", 8,
                "nextDoclingRequestId", "task-1:item-1:8",
                "updatedAt", LocalDateTime.now()));

        assertThat(boundSql.getSql())
                .contains("iti.docling_request_id = ?")
                .contains("iti.parse_attempt = ?")
                .contains("and iti.parse_attempt = ?")
                .doesNotContain("concat(")
                .doesNotContain("parse_attempt + 1");
        assertThat(boundSql.getSql())
                .contains("iti.execution_epoch = iti.execution_epoch + 1")
                .contains("iti.execution_stage = 'PARSE_SUBMIT'")
                .contains("iti.stage_attempt = 0")
                .contains("iti.stage_retry_count = 0")
                .contains("iti.next_action_at = ?")
                .contains("iti.lease_token = null")
                .contains("iti.parse_request_snapshot = null")
                .contains("iti.parse_result_object_key = null")
                .contains("iti.embedding_result_object_key = null");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly(
                        "updatedAt",
                        "nextDoclingRequestId",
                        "nextParseAttempt",
                        "updatedAt",
                        "kbId",
                        "taskId",
                        "itemId",
                        "expectedParseAttempt");
    }

    @Test
    void claim_shouldUseSkipLockedDatabaseLeaseAndStableStageStart() throws Exception {
        Configuration configuration = loadConfiguration();
        String namespace = IngestionTaskMapper.class.getName() + ".";

        BoundSql select = configuration.getMappedStatement(namespace + "selectClaimableItemForUpdate")
                .getBoundSql(Map.of("itemId", "item-1"));
        assertThat(select.getSql())
                .contains("for update skip locked")
                .contains("lease_until <= current_timestamp(6)");

        IngestionTaskItemRecord candidate = new IngestionTaskItemRecord();
        candidate.setId("item-1");
        candidate.setExecutionEpoch(3L);
        candidate.setExecutionStage(IngestionExecutionStage.PARSE_WAIT.name());
        candidate.setStageAttempt(4);
        BoundSql claim = configuration.getMappedStatement(namespace + "claimItem")
                .getBoundSql(Map.of(
                        "item", candidate,
                        "leaseToken", "lease-1",
                        "leaseSeconds", 60L));
        assertThat(claim.getSql())
                .contains("stage_retry_count = stage_retry_count + case")
                .contains("when ? is not null then 1")
                .contains("lease_until = timestampadd(second, ?, current_timestamp(6))")
                .contains("stage_attempt = stage_attempt + 1")
                .contains("stage_started_at = coalesce(stage_started_at, current_timestamp(6))")
                .contains("status = 'RUNNING'")
                .contains("execution_epoch = ?")
                .contains("execution_stage = ?")
                .contains("stage_attempt = ?");
    }

    @Test
    void transition_shouldFenceCompleteNextStateWithoutRequiringLiveLease() throws Exception {
        Configuration configuration = loadConfiguration();
        String statement = IngestionTaskMapper.class.getName() + ".transitionClaim";
        IngestionClaimTransition transition = IngestionClaimTransition.builder()
                .itemId("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .executionEpoch(2L)
                .expectedExecutionStage(IngestionExecutionStage.PARSE_WAIT)
                .expectedStageAttempt(3)
                .leaseToken("lease-1")
                .nextExecutionStage(IngestionExecutionStage.PARSE_PERSIST)
                .nextStageAttempt(0)
                .nextStageRetryCount(0)
                .nextActionAt(LocalDateTime.now())
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(30)
                .parseAttempt(1)
                .updatedBy("user-1")
                .updatedAt(LocalDateTime.now())
                .build();

        BoundSql boundSql = configuration.getMappedStatement(statement).getBoundSql(transition);
        assertThat(boundSql.getSql())
                .contains("execution_stage = ?")
                .contains("stage_attempt = ?")
                .contains("stage_retry_count = ?")
                .contains("stage_started_at = ?")
                .contains("next_action_at = ?")
                .contains("parse_request_snapshot = ?")
                .contains("parse_result_object_key = ?")
                .contains("embedding_result_object_key = ?")
                .contains("lease_token = null")
                .contains("lease_until = null")
                .contains("execution_epoch = ?")
                .contains("execution_stage = ?")
                .contains("stage_attempt = ?")
                .contains("lease_token = ?")
                .contains("status = 'RUNNING'")
                .doesNotContain("lease_until >")
                .doesNotContain("lease_until &gt;");
    }

    private Configuration loadConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(IngestionTaskMapper.class);
        String resource = "mapper/ingestion/IngestionTaskMapper.xml";
        XMLMapperBuilder builder = new XMLMapperBuilder(
                Resources.getResourceAsStream(resource),
                configuration,
                resource,
                configuration.getSqlFragments());
        builder.parse();
        return configuration;
    }
}
