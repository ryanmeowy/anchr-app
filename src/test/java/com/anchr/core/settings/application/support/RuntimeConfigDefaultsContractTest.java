package com.anchr.core.settings.application.support;

import com.anchr.core.conversation.application.agent.AgentRuntimeSettings;
import com.anchr.core.conversation.application.model.ConversationRuntimeSettings;
import com.anchr.core.ingestion.application.model.IngestionRuntimeSettings;
import com.anchr.core.kb.application.model.OutboxRuntimeSettings;
import com.anchr.core.search.application.model.SearchRuntimeSettings;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigDefaultsContractTest {

    private final RuntimeConfigCatalog catalog = new RuntimeConfigCatalog();

    @Test
    void callerDefaultsShouldMatchTheManagementPageDefaults() {
        var unit = RuntimeConfigTestUnits.defaults();
        SearchRuntimeSettings search = SearchRuntimeSettings.load(unit);
        ConversationRuntimeSettings conversation =
                ConversationRuntimeSettings.load(unit);
        AgentRuntimeSettings agent = AgentRuntimeSettings.load(unit);
        IngestionRuntimeSettings ingestion = IngestionRuntimeSettings.load(unit);
        OutboxRuntimeSettings outbox = OutboxRuntimeSettings.load(unit);

        assertThat(serializedDefaults(RuntimeConfigType.SEARCH)).isEqualTo(Map.ofEntries(
                Map.entry("rankConstant", Integer.toString(search.rankConstant())),
                Map.entry("candidateMultiplier", Integer.toString(search.candidateMultiplier())),
                Map.entry("maxCandidates", Integer.toString(search.maxCandidates())),
                Map.entry("textTopK", Integer.toString(search.textTopK())),
                Map.entry("documentImageTopK", Integer.toString(search.documentImageTopK())),
                Map.entry("textSimilarity", Float.toString(search.textSimilarity())),
                Map.entry("documentImageSimilarity", Float.toString(search.documentImageSimilarity())),
                Map.entry("maxDocChars", Integer.toString(search.maxDocChars())),
                Map.entry("windowEnabled", Boolean.toString(search.windowEnabled())),
                Map.entry("windowSize", Integer.toString(search.windowSize())),
                Map.entry("windowFactor", Integer.toString(search.windowFactor())),
                Map.entry("windowMin", Integer.toString(search.windowMin())),
                Map.entry("windowMax", Integer.toString(search.windowMax())),
                Map.entry("fusionAlpha", Double.toString(search.fusionAlpha())),
                Map.entry("fusionBeta", Double.toString(search.fusionBeta()))));
        assertThat(serializedDefaults(RuntimeConfigType.CONVERSATION))
                .containsExactlyInAnyOrderEntriesOf(
                Map.of(
                        "intentRoutingEnabled", Boolean.toString(conversation.intentRoutingEnabled()),
                        "intentContextTurnLimit", Integer.toString(conversation.intentContextTurnLimit()),
                        "intentTimeoutSeconds", Long.toString(conversation.intentTimeout().toSeconds()),
                        "legacyEvidenceFallbackEnabled",
                        Boolean.toString(conversation.legacyEvidenceFallbackEnabled())));
        assertThat(serializedDefaults(RuntimeConfigType.AGENT))
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("enabled", Boolean.toString(agent.enabled())),
                        Map.entry("toolCallMode", agent.toolCallMode().name()),
                        Map.entry("nativeToolChoice", agent.nativeToolChoice().name()),
                        Map.entry(
                                "fallbackToTraditional",
                                Boolean.toString(agent.fallbackToTraditional())),
                        Map.entry("maxSteps", Integer.toString(agent.maxSteps())),
                        Map.entry(
                                "maxToolCalls",
                                Integer.toString(agent.maxToolCalls())),
                        Map.entry(
                                "totalTimeoutSeconds",
                                Long.toString(agent.totalTimeout().toSeconds())),
                        Map.entry(
                                "modelTimeoutSeconds",
                                Long.toString(agent.modelTimeout().toSeconds())),
                        Map.entry(
                                "taskTimeoutSeconds",
                                Long.toString(agent.taskTimeout().toSeconds())),
                        Map.entry(
                                "taskModelTimeoutSeconds",
                                Long.toString(agent.taskModelTimeout().toSeconds())),
                        Map.entry(
                                "taskMaxRetries",
                                Integer.toString(agent.taskMaxRetries())),
                        Map.entry(
                                "runtimeSnapshotTtlSeconds",
                                Long.toString(agent.runtimeSnapshotTtl().toSeconds())),
                        Map.entry(
                                "summaryMaxDocuments",
                                Integer.toString(agent.summaryMaxDocuments())),
                        Map.entry(
                                "summaryMaxSegments",
                                Integer.toString(agent.summaryMaxSegments())),
                        Map.entry(
                                "summaryMaxChars",
                                Integer.toString(agent.summaryMaxChars())),
                        Map.entry(
                                "summaryBatchChars",
                                Integer.toString(agent.summaryBatchChars()))));
        assertThat(serializedDefaults(RuntimeConfigType.INGESTION))
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry(
                                "claimBatchSize",
                                Integer.toString(ingestion.claimBatchSize())),
                        Map.entry(
                                "parsePollIntervalSeconds",
                                Long.toString(ingestion.parsePollInterval().toSeconds())),
                        Map.entry(
                                "parseStageTimeoutMinutes",
                                Long.toString(ingestion.parseStageTimeout().toMinutes())),
                        Map.entry(
                                "stageMaxRetries",
                                Integer.toString(ingestion.stageMaxRetries())),
                        Map.entry(
                                "embeddingMinIntervalMs",
                                Long.toString(ingestion.embeddingMinIntervalMs())),
                        Map.entry(
                                "embeddingRateLimitMaxAttempts",
                                Integer.toString(
                                        ingestion.embeddingRateLimitMaxAttempts())),
                        Map.entry(
                                "embeddingRateLimitBackoffMs",
                                Long.toString(ingestion.embeddingRateLimitBackoffMs())),
                        Map.entry(
                                "embeddedImageUploadEnabled",
                                Boolean.toString(
                                        ingestion.embeddedImageUploadEnabled())),
                        Map.entry(
                                "doclingMaxResponseMiB",
                                Integer.toString(
                                        ingestion.doclingMaxResponseBytes()
                                                / 1024 / 1024))));
        assertThat(serializedDefaults(RuntimeConfigType.OUTBOX))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "batchSize", Integer.toString(outbox.batchSize()),
                        "maxAttempts", Integer.toString(outbox.maxAttempts()),
                        "retentionDays", Long.toString(outbox.retentionDays()),
                        "cleanupBatchSize", Integer.toString(outbox.cleanupBatchSize())));
    }

    private Map<String, String> serializedDefaults(RuntimeConfigType type) {
        return catalog.defaults(type).entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().propertyName(),
                        Map.Entry::getValue));
    }
}
