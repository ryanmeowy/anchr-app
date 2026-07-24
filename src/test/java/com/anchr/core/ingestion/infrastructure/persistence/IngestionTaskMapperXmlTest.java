package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionPublicProjectionPolicy;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItemStatus;
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
    void mapperXml_shouldExposeSplitExecutionStatementsOnly() throws Exception {
        Configuration configuration = loadConfiguration();

        assertThat(configuration.hasStatement(NAMESPACE + "insertParseAttempt")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "insertExecution")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "insertArtifact")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "pointItemToExecution")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "selectClaimableItemForUpdate")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "claimExecution")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "transitionExecution")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "projectTransitionToItem")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "selectFailedItemForRetryForUpdate"))
                .isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "resetFailedItemPointer")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "findRetryItem")).isTrue();
        assertThat(configuration.hasStatement(
                NAMESPACE + "findMaxTargetIndexGeneration")).isTrue();
        assertThat(configuration.hasStatement(
                NAMESPACE + "assignTargetIndexGeneration")).isTrue();

        assertThat(configuration.hasStatement(NAMESPACE + "claimItem")).isFalse();
        assertThat(configuration.hasStatement(NAMESPACE + "transitionClaim")).isFalse();
        assertThat(configuration.hasStatement(NAMESPACE + "resetFailedItem")).isFalse();
        assertThat(configuration.hasStatement(NAMESPACE + "markItemRunning")).isFalse();
        assertThat(configuration.hasStatement(NAMESPACE + "markItemSuccess")).isFalse();
        assertThat(configuration.hasStatement(NAMESPACE + "markItemFailed")).isFalse();
    }

    @Test
    void publicItemReads_shouldNotLoadLeaseSnapshotOrArtifactPayloads() throws Exception {
        Configuration configuration = loadConfiguration();

        String listSql = sql(configuration, "listItems", Map.of("taskId", "task-1"));
        String findSql = sql(configuration, "findItem", Map.of(
                "kbId", "kb-1",
                "taskId", "task-1",
                "itemId", "item-1"));

        assertPublicItemProjectionIsNarrow(listSql);
        assertPublicItemProjectionIsNarrow(findSql);
    }

    @Test
    void claimCandidate_shouldExcludeSourceErrorAndParseSnapshotPayloads() throws Exception {
        Configuration configuration = loadConfiguration();

        String sql = sql(configuration, "selectClaimableItemForUpdate",
                Map.of("itemId", "item-1"));

        assertThat(sql)
                .contains("ie.id as execution_id")
                .contains("ie.phase")
                .contains("ie.claim_version")
                .contains("inner join ingestion_item_parse_attempt ipa")
                .contains("ipa.id = ie.parse_attempt_id")
                .contains("ipa.item_id = ie.item_id")
                .contains("for update skip locked")
                .doesNotContain("source_url")
                .doesNotContain("error_message")
                .doesNotContain("request_snapshot")
                .doesNotContain("object_key");
    }

    @Test
    void claimCandidateLists_shouldRequireParseAttemptOwnership() throws Exception {
        Configuration configuration = loadConfiguration();

        String allTasks = sql(
                configuration, "listClaimableItemIds", Map.of("limit", 10));
        String oneTask = sql(configuration, "listClaimableItemIdsByTask", Map.of(
                "taskId", "task-1",
                "limit", 10));

        for (String sql : List.of(allTasks, oneTask)) {
            assertThat(sql)
                    .contains("inner join ingestion_item_parse_attempt ipa")
                    .contains("ipa.id = ie.parse_attempt_id")
                    .contains("ipa.item_id = ie.item_id");
        }
    }

    @Test
    void nonParseClaimedExecution_shouldNotSelectParseSnapshotPayload() throws Exception {
        Configuration configuration = loadConfiguration();

        String sql = sql(configuration, "findClaimedExecution", Map.of(
                "itemId", "item-1",
                "leaseToken", "lease-1",
                "includeParseSnapshot", false));

        assertThat(sql)
                .contains("null as request_snapshot")
                .doesNotContain("ipa.request_snapshot as request_snapshot");
    }

    @Test
    void parseClaimedExecution_shouldSelectParseSnapshotPayload() throws Exception {
        Configuration configuration = loadConfiguration();

        String sql = sql(configuration, "findClaimedExecution", Map.of(
                "itemId", "item-1",
                "leaseToken", "lease-1",
                "includeParseSnapshot", true));

        assertThat(sql).contains("ipa.request_snapshot as request_snapshot");
    }

    @Test
    void claimedExecution_shouldExcludePublicDisplayAndUnusedExecutionFields() throws Exception {
        Configuration configuration = loadConfiguration();

        String sql = sql(configuration, "findClaimedExecution", Map.of(
                "itemId", "item-1",
                "leaseToken", "lease-1",
                "includeParseSnapshot", false));

        assertThat(sql)
                .contains("iti.progress as item_progress")
                .contains("iti.target_index_generation")
                .contains("ie.phase")
                .contains("ie.claim_version")
                .doesNotContain("iti.file_name")
                .doesNotContain("iti.file_hash")
                .doesNotContain("iti.stage as item_stage")
                .doesNotContain("iti.status as item_status")
                .doesNotContain("iti.error_code")
                .doesNotContain("iti.error_message")
                .doesNotContain("ie.execution_kind")
                .doesNotContain("ie.execution_status,")
                .doesNotContain("ipa.status as parse_attempt_status");
        assertThat(sql)
                .contains("inner join ingestion_item_parse_attempt ipa")
                .contains("ipa.item_id = iti.id");
    }

    @Test
    void targetGenerationAllocation_shouldBeStableOnTheItem() throws Exception {
        Configuration configuration = loadConfiguration();

        String maxSql = sql(configuration, "findMaxTargetIndexGeneration",
                Map.of("assetId", "asset-1"));
        String assignSql = sql(configuration, "assignTargetIndexGeneration", Map.of(
                "itemId", "item-1",
                "assetId", "asset-1",
                "targetIndexGeneration", 4L,
                "updatedAt", LocalDateTime.now()));

        assertThat(maxSql)
                .contains("max(target_index_generation)")
                .contains("asset_id = ?");
        assertThat(assignSql)
                .contains("target_index_generation = ?")
                .contains("id = ?")
                .contains("asset_id = ?")
                .contains("target_index_generation is null")
                .doesNotContain("ingestion_item_execution");
    }

    @Test
    void updateClaimContext_shouldFenceStableParseIdentityAndSnapshot() throws Exception {
        Configuration configuration = loadConfiguration();
        IngestionClaimContext context = IngestionClaimContext.builder()
                .itemId("item-1")
                .executionEpoch(2L)
                .expectedExecutionStage(IngestionExecutionStage.PARSE_SUBMIT)
                .claimVersion(1)
                .leaseToken("lease-1")
                .parseAttempt(3)
                .doclingRequestId("task-1:item-1:3")
                .doclingJobId("job-1")
                .sourceRevision("v1:revision")
                .parseRequestSnapshot("{\"fileName\":\"sample.pdf\"}")
                .build();

        String sql = sql(configuration, "updateClaimContext", context);

        assertThat(sql)
                .contains("update ingestion_item_parse_attempt ipa")
                .contains("iti.current_execution_id = ie.id")
                .contains("ie.phase = ?")
                .contains("ie.claim_version = ?")
                .contains("ie.execution_status = 'ACTIVE'")
                .contains("ipa.attempt_no = ?")
                .contains("ipa.request_id is null or ipa.request_id = ?")
                .contains("ipa.source_revision is null or ipa.source_revision = ?")
                .contains("ipa.request_snapshot is null")
                .contains("ipa.request_snapshot = cast(? as json)");
    }

    @Test
    void transition_shouldFenceCurrentExecutionPhaseStatusAndClaimVersion() throws Exception {
        Configuration configuration = loadConfiguration();
        IngestionClaimTransition transition = IngestionClaimTransition.builder()
                .itemId("item-1")
                .taskId("task-1")
                .kbId("kb-1")
                .executionEpoch(2L)
                .expectedExecutionStage(IngestionExecutionStage.PARSE_WAIT)
                .expectedClaimVersion(3)
                .leaseToken("lease-1")
                .nextExecutionStage(IngestionExecutionStage.PARSE_PERSIST)
                .nextStageRetryCount(0)
                .nextActionAt(LocalDateTime.now())
                .stage(IngestionStage.PARSE)
                .status(IngestionTaskItemStatus.RUNNING)
                .progress(30)
                .parseAttempt(1)
                .updatedBy("user-1")
                .updatedAt(LocalDateTime.now())
                .build();

        String executionSql = sql(configuration, "transitionExecution", transition);
        assertThat(executionSql)
                .contains("iti.current_execution_id = ie.id")
                .contains("inner join ingestion_item_parse_attempt ipa")
                .contains("ipa.id = ie.parse_attempt_id")
                .contains("ipa.item_id = ie.item_id")
                .contains("ie.phase = ?")
                .contains("ie.claim_version = ?")
                .contains("ie.execution_status = 'ACTIVE'")
                .contains("ie.lease_token = ?")
                .doesNotContain("lease_until >");

        String projectionSql = sql(configuration, "projectTransitionToItem", transition);
        assertThat(projectionSql)
                .contains("ie.id = iti.current_execution_id")
                .contains("inner join ingestion_item_parse_attempt ipa")
                .contains("ipa.id = ie.parse_attempt_id")
                .contains("ie.execution_epoch = ?")
                .contains("ie.claim_version = ?")
                .contains("ie.execution_status = case")
                .contains("? >= iti.progress");
    }

    @Test
    void explicitRetry_shouldLockFailureInsertNewRowsAndCasCurrentPointer() throws Exception {
        Configuration configuration = loadConfiguration();

        String selectSql = sql(configuration, "selectFailedItemForRetryForUpdate", Map.of(
                "kbId", "kb-1",
                "taskId", "task-1",
                "itemId", "item-1",
                "expectedParseAttempt", 7));
        assertThat(selectSql)
                .contains("iti.current_execution_id")
                .contains("ie.execution_status")
                .contains("ipa.attempt_no")
                .contains("iti.status = 'FAILED'")
                .contains("ie.execution_status = 'FAILED'")
                .contains("inner join ingestion_item_execution ie")
                .contains("inner join ingestion_item_parse_attempt ipa")
                .doesNotContain("UNSUPPORTED_FILE_TYPE")
                .doesNotContain("coalesce(")
                .contains("for update")
                .doesNotContain("iti.execution_epoch")
                .doesNotContain("iti.parse_attempt")
                .doesNotContain("iti.source_revision");

        String pointerSql = sql(configuration, "resetFailedItemPointer", Map.of(
                "kbId", "kb-1",
                "taskId", "task-1",
                "itemId", "item-1",
                "expectedCurrentExecutionId", 41L,
                "nextExecutionId", 42L,
                "projection", IngestionPublicProjectionPolicy.explicitRetry(),
                "updatedAt", LocalDateTime.now()));
        assertThat(pointerSql)
                .contains("inner join ingestion_item_execution next_ie")
                .contains("next_ie.item_id = iti.id")
                .contains("next_ie.execution_status = 'ACTIVE'")
                .contains("inner join ingestion_item_parse_attempt next_ipa")
                .contains("next_ipa.id = next_ie.parse_attempt_id")
                .contains("next_ipa.item_id = iti.id")
                .contains("iti.current_execution_id = ?")
                .contains("iti.stage = ?")
                .contains("iti.status = ?")
                .contains("iti.progress = ?")
                .doesNotContain("iti.status = 'PENDING'")
                .doesNotContain("iti.stage = 'UPLOAD'")
                .contains("iti.current_execution_id = ?");

        assertThat(configuration.hasStatement(NAMESPACE + "insertParseAttempt")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + "insertExecution")).isTrue();
    }

    @Test
    void retryReads_shouldRequireNormalizedExecutionOwnership() throws Exception {
        Configuration configuration = loadConfiguration();
        Map<String, Object> parameter = Map.of(
                "kbId", "kb-1",
                "taskId", "task-1",
                "itemId", "item-1",
                "expectedParseAttempt", 1);

        for (String statement : new String[]{
                "findRetryItem", "listFailedItems", "selectFailedItemForRetryForUpdate"}) {
            String sql = sql(configuration, statement, parameter);
            assertThat(sql)
                    .doesNotContain("iti.execution_epoch")
                    .doesNotContain("iti.parse_attempt")
                    .doesNotContain("iti.source_revision")
                    .doesNotContain("coalesce(")
                    .doesNotContain("UNSUPPORTED_FILE_TYPE")
                    .contains("inner join ingestion_item_execution ie")
                    .contains("inner join ingestion_item_parse_attempt ipa")
                    .contains("ie.execution_status = 'FAILED'")
                    .contains("ipa.attempt_no");
        }
    }

    @Test
    void currentPointerWrites_shouldRequireExecutionOwnership() throws Exception {
        Configuration configuration = loadConfiguration();

        String initialPointerSql = sql(configuration, "pointItemToExecution", Map.of(
                "itemId", "item-1",
                "executionId", 42L,
                "updatedAt", LocalDateTime.now()));
        assertThat(initialPointerSql)
                .contains("inner join ingestion_item_execution ie")
                .contains("ie.id = ?")
                .contains("ie.item_id = iti.id")
                .contains("inner join ingestion_item_parse_attempt ipa")
                .contains("ipa.id = ie.parse_attempt_id")
                .contains("ipa.item_id = iti.id");
    }

    @Test
    void heldClaimFences_shouldRequireParseAttemptOwnership() throws Exception {
        Configuration configuration = loadConfiguration();
        Map<String, Object> claim = Map.of(
                "itemId", "item-1",
                "executionEpoch", 1L,
                "expectedExecutionStage", IngestionExecutionStage.EMBED,
                "claimVersion", 2L,
                "leaseToken", "lease-1",
                "leaseSeconds", 60L);

        for (String statement : List.of("renewClaim", "findCurrentClaimForUpdate")) {
            String sql = sql(configuration, statement, claim);
            assertThat(sql)
                    .contains("inner join ingestion_item_parse_attempt ipa")
                    .contains("ipa.id = ie.parse_attempt_id")
                    .contains("ipa.item_id = ie.item_id");
        }
    }

    private void assertPublicItemProjectionIsNarrow(String sql) {
        assertThat(sql)
                .contains("iti.updated_at")
                .doesNotContain("lease_token")
                .doesNotContain("request_snapshot")
                .doesNotContain("object_key")
                .doesNotContain("current_execution_id")
                .doesNotContain("claim_version")
                .doesNotContain("parse_attempt")
                .doesNotContain("ingestion_item_execution");
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
        XMLMapperBuilder builder = new XMLMapperBuilder(
                Resources.getResourceAsStream(resource),
                configuration,
                resource,
                configuration.getSqlFragments());
        builder.parse();
        return configuration;
    }
}
