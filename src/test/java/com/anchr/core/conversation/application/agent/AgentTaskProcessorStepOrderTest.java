package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.conversation.application.AnswerEventPublisher;
import com.anchr.core.conversation.application.ConversationCitationReasonEnricher;
import com.anchr.core.conversation.application.acl.ConversationKnowledgeAcl;
import com.anchr.core.conversation.application.acl.ConversationRetrievalAcl;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.application.assembler.ConversationTurnCodec;
import com.anchr.core.conversation.application.impl.EvidenceCleaningService;
import com.anchr.core.conversation.application.model.EvidenceCheckReport;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.AgentStep;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import com.anchr.core.conversation.domain.repository.AgentTraceRepository;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.anchr.core.testsupport.EvidenceCleaningTestSupport;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskProcessorStepOrderTest {

    private final AgentTraceRepository traceRepository = mock(AgentTraceRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentRuntimeSnapshotService snapshotService = mock(AgentRuntimeSnapshotService.class);
    private final List<AgentStep> storedSteps = new ArrayList<>();
    private AgentTaskProcessor processor;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(traceRepository.lockRun("run-1")).thenReturn(true);
        when(traceRepository.findSteps("run-1")).thenAnswer(ignored -> new ArrayList<>(storedSteps));
        doAnswer(invocation -> {
            AgentStep saved = invocation.getArgument(0);
            storedSteps.removeIf(existing -> existing.getStepId().equals(saved.getStepId()));
            storedSteps.add(saved);
            return null;
        }).when(traceRepository).saveStep(any(AgentStep.class));

        processor = new AgentTaskProcessor(
                mock(AgentTaskRepository.class),
                mock(ConversationRepository.class),
                traceRepository,
                mock(ConversationKnowledgeAcl.class),
                mock(ConversationRetrievalAcl.class),
                mock(ConversationGenerationPort.class),
                mock(ConversationCitationMapper.class),
                mock(ConversationTurnCodec.class),
                new ObjectMapper(),
                RuntimeConfigTestUnits.defaults(),
                transactionTemplate,
                mock(AnswerEventPublisher.class),
                Runnable::run,
                snapshotService,
                new AgentCitationPolicy(),
                mock(ConversationCitationReasonEnricher.class), EvidenceCleaningTestSupport.allowing());

        storedSteps.add(step("model-1", 1, "MODEL_DECISION", "TOOL_CALLS", 1));
        storedSteps.add(step("tool-2", 2, "TOOL_RESULT", "SUCCESS", 1));
    }

    @Test
    void summaryInspectionFailureIsPermanentAndNeverCallsSummaryModel() throws Exception {
        var repository = mock(AgentTaskRepository.class);
        var task = task(1);
        task.setStatus("RUNNING");
        task.setLeaseOwner((String) ReflectionTestUtils.getField(processor, "owner"));
        when(repository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(repository.saveClaimed(any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(processor, "taskRepository", repository);
        var cleaner = mock(EvidenceCleaningService.class);
        when(cleaner.clean(any(), ArgumentMatchers.anyBoolean(), any())).thenReturn(
                new EvidenceCleaningService.Result(false,
                        NullNode.instance,
                        EvidenceCheckReport.failed("invalid_check_response")));
        ReflectionTestUtils.setField(processor, "evidenceCleaner", cleaner);
        Class<?> evidenceClass = Class.forName(AgentTaskProcessor.class.getName() + "$EvidenceText");
        var constructor = evidenceClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var candidate = ConversationRetrievalCandidate.builder()
                .assetId("a").segmentId("s").content("untrusted body").build();
        Object evidence = constructor.newInstance(candidate, candidate.getContent());
        Assertions.assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(processor,
                "inspectSummaryEvidence", task, List.of(evidence), System.currentTimeMillis() + 60_000,
                AgentRuntimeSettings.load(RuntimeConfigTestUnits.defaults())))
                .hasMessageContaining("本次资料未通过证据检查");
        Mockito.verifyNoInteractions(ReflectionTestUtils.getField(processor, "generationPort"));
        assertThat(taskStages()).anySatisfy(step -> assertThat(step.getOutputSummaryJson()).contains("evidenceCheck"));
    }

    @Test
    void taskStages_shouldContinueTheRunSequenceWithoutEncodingStageNamesInOrder() {
        AgentTask task = task(1);

        record(task, "READING", "RUNNING", 5, Map.of());
        record(task, "READING", "COMPLETED", 35, Map.of());
        record(task, "MAP_SUMMARY", "RUNNING", 35, Map.of("segmentCount", 57));
        record(task, "MAP_SUMMARY", "COMPLETED", 75, Map.of("segmentCount", 57));
        record(task, "REDUCE_SUMMARY", "COMPLETED", 90, Map.of("batchCount", 2));
        record(task, "FINALIZING", "COMPLETED", 100, Map.of("citationCount", 9));
        record(task, "COMPLETED", "COMPLETED", 100, Map.of("citationCount", 9));

        assertThat(storedSteps.stream()
                .sorted(Comparator.comparingInt(AgentStep::getStepOrder))
                .map(AgentStep::getStepOrder)
                .toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(taskStages()).extracting(AgentStep::getDecisionCode)
                .containsExactly("READING", "MAP_SUMMARY", "REDUCE_SUMMARY", "FINALIZING", "COMPLETED");
        assertThat(taskStages()).allSatisfy(step ->
                assertThat(step.getOutputSummaryJson()).doesNotContain("taskStage"));
    }

    @Test
    void sameStageAndAttempt_shouldReuseItsIdentityOrderAndCreatedAt() {
        AgentTask task = task(1);
        record(task, "MAP_SUMMARY", "RUNNING", 35, Map.of("segmentCount", 57));
        AgentStep first = taskStage("MAP_SUMMARY", 1);

        record(task, "MAP_SUMMARY", "COMPLETED", 75, Map.of("batchCount", 2));
        AgentStep completed = taskStage("MAP_SUMMARY", 1);

        assertThat(completed.getStepId()).isEqualTo(first.getStepId());
        assertThat(completed.getStepOrder()).isEqualTo(3);
        assertThat(completed.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(taskStages()).hasSize(1);
        assertThat(completed.getOutputSummaryJson()).contains("\"segmentCount\":57", "\"batchCount\":2");
    }

    @Test
    void retry_shouldCreateNewStageStepsAndKeepThePreviousAttempt() {
        AgentTask task = task(1);
        record(task, "READING", "FAILED", 5, Map.of());
        record(task, "RETRY_WAIT", "RUNNING", 5, Map.of());

        task.setAttemptCount(2);
        record(task, "READING", "RUNNING", 5, Map.of());

        assertThat(taskStages()).extracting(AgentStep::getStepOrder)
                .containsExactly(3, 4, 5);
        assertThat(taskStages()).filteredOn(step -> "READING".equals(step.getDecisionCode()))
                .extracting(AgentStep::getAttempt).containsExactly(1, 2);
        assertThat(taskStage("READING", 1).getStepId())
                .isNotEqualTo(taskStage("READING", 2).getStepId());
    }

    @Test
    void generationUsage_shouldFindTheStageByCodeAndAttemptInsteadOfFixedOrder() {
        AgentTask task = task(1);
        task.setCurrentStage("MAP_SUMMARY");
        record(task, "MAP_SUMMARY", "RUNNING", 35, Map.of());

        ReflectionTestUtils.invokeMethod(
                processor, "recordGenerationUsage", task, task.getCurrentStage(), 1, 120, 40, 900L, 150L, true);

        AgentStep stage = taskStage("MAP_SUMMARY", 1);
        assertThat(stage.getStepOrder()).isEqualTo(3);
        assertThat(stage.getPromptTokens()).isEqualTo(120);
        assertThat(stage.getCompletionTokens()).isEqualTo(40);
        assertThat(stage.getOutputSummaryJson())
                .contains("\"modelCallCount\":1", "\"modelLatencyMs\":900",
                        "\"firstTokenMs\":150", "\"streaming\":true");
        verify(traceRepository).addRunTokenUsage("run-1", 120, 40);
    }

    @Test
    void cancellation_shouldUpdateTheCurrentStageAndAppendOneTerminalStep() {
        AgentTask task = task(1);
        task.setCurrentStage("FINALIZING");
        record(task, "FINALIZING", "RUNNING", 90, Map.of());

        processor.recordCancellation(task);

        assertThat(taskStage("FINALIZING", 1).getStepOrder()).isEqualTo(3);
        assertThat(taskStage("FINALIZING", 1).getStatus()).isEqualTo("CANCELLED");
        assertThat(taskStage("CANCELLED", 1).getStepOrder()).isEqualTo(4);
        assertThat(taskStage("CANCELLED", 1).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void inspectionUsageTargetsExplicitStageAndCountsAllCallsOnce() {
        AgentTask task = task(1);
        task.setCurrentStage("READING");
        record(task, "READING", "RUNNING", 5, Map.of());
        record(task, "EVIDENCE_CHECK_1", "RUNNING", 30, Map.of());
        ReflectionTestUtils.invokeMethod(processor, "recordGenerationUsage", task,
                "EVIDENCE_CHECK_1", 3, 120, 40, 900L, null, false);
        assertThat(taskStage("READING", 1).getPromptTokens()).isZero();
        var checked = taskStage("EVIDENCE_CHECK_1", 1);
        assertThat(checked.getPromptTokens()).isEqualTo(120);
        assertThat(checked.getCompletionTokens()).isEqualTo(40);
        assertThat(checked.getOutputSummaryJson()).contains("\"modelCallCount\":3", "\"modelLatencyMs\":900");
        verify(traceRepository).addRunTokenUsage(task.getRunId(), 120, 40);
        checked.setCreatedAt(System.currentTimeMillis() - 1000);
        record(task, "EVIDENCE_CHECK_1", "FAILED", 30, Map.of());
        record(task, "EVIDENCE_CHECK_1", "COMPLETED", 35, Map.of());
        assertThat(taskStage("EVIDENCE_CHECK_1", 1).getStatus()).isEqualTo("FAILED");
        assertThat(taskStage("EVIDENCE_CHECK_1", 1).getLatencyMs()).isGreaterThanOrEqualTo(1000);
    }

    @Test
    void eachSummaryBatchEntersItsInspectionStageBeforeCallingModel() throws Exception {
        var repository = mock(AgentTaskRepository.class);
        var task = task(1);
        task.setStatus("RUNNING"); task.setCurrentStage("READING");
        task.setLeaseOwner((String) ReflectionTestUtils.getField(processor, "owner"));
        when(repository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(repository.saveClaimed(any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(processor, "taskRepository", repository);
        var cleaner = mock(EvidenceCleaningService.class);
        var delegate = EvidenceCleaningTestSupport.allowing();
        List<String> entered = new ArrayList<>();
        when(cleaner.clean(any(), ArgumentMatchers.anyBoolean(), any())).thenAnswer(call -> {
            entered.add(task.getCurrentStage());
            assertThat(taskStage(task.getCurrentStage(), 1).getStatus()).isEqualTo("RUNNING");
            return delegate.clean(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        });
        ReflectionTestUtils.setField(processor, "evidenceCleaner", cleaner);
        Class<?> evidenceClass = Class.forName(AgentTaskProcessor.class.getName() + "$EvidenceText");
        var constructor = evidenceClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        List<Object> evidence = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var candidate = ConversationRetrievalCandidate.builder().assetId("a").segmentId("s" + i)
                    .content("normal ".repeat(1700)).build();
            evidence.add(constructor.newInstance(candidate, candidate.getContent()));
        }
        List<?> safe = ReflectionTestUtils.invokeMethod(processor, "inspectSummaryEvidence", task, evidence,
                System.currentTimeMillis() + 60_000, AgentRuntimeSettings.load(RuntimeConfigTestUnits.defaults()));
        assertThat(safe).hasSize(2);
        assertThat(entered).containsExactly("EVIDENCE_CHECK_1", "EVIDENCE_CHECK_2");
        for (String stage : entered) {
            assertThat(taskStage(stage, 1).getStatus()).isEqualTo("COMPLETED");
            assertThat(taskStage(stage, 1).getOutputSummaryJson()).contains("\"modelCallCount\":1", "evidenceCheck");
        }
        processor.recordCancellation(task);
        assertThat(taskStage("EVIDENCE_CHECK_2", 1).getStatus()).isEqualTo("CANCELLED");
        record(task, "EVIDENCE_CHECK_2", "COMPLETED", 35, Map.of());
        assertThat(taskStage("EVIDENCE_CHECK_2", 1).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void legacyFixedOrderStage_shouldBeUpdatedWithoutCreatingADuplicate() {
        AgentStep legacy = step("legacy-reading", 101, "TASK_STAGE", "READING", 1);
        legacy.setOutputSummaryJson("{\"taskStage\":\"READING\",\"progress\":5}");
        storedSteps.add(legacy);

        AgentTask task = task(1);
        record(task, "READING", "COMPLETED", 35, Map.of());
        record(task, "MAP_SUMMARY", "RUNNING", 35, Map.of());

        AgentStep reading = taskStage("READING", 1);
        assertThat(reading.getStepId()).isEqualTo("legacy-reading");
        assertThat(reading.getStepOrder()).isEqualTo(101);
        assertThat(reading.getOutputSummaryJson()).doesNotContain("taskStage");
        assertThat(taskStage("MAP_SUMMARY", 1).getStepOrder()).isEqualTo(102);
    }

    @Test
    void missingRun_shouldNotCreateAnOrphanStepOrPublishActivity() {
        when(traceRepository.lockRun("run-1")).thenReturn(false);

        record(task(1), "READING", "RUNNING", 5, Map.of());

        assertThat(taskStages()).isEmpty();
        verify(traceRepository, never()).saveStep(any());
        verify(snapshotService, never()).publishActivity(any());
    }

    private void record(AgentTask task, String stage, String status, int progress,
                        Map<String, Object> details) {
        ReflectionTestUtils.invokeMethod(
                processor, "recordTaskStage", task, stage, status, progress, null, details);
    }

    private List<AgentStep> taskStages() {
        return storedSteps.stream()
                .filter(step -> "TASK_STAGE".equals(step.getStepType()))
                .sorted(Comparator.comparingInt(AgentStep::getStepOrder))
                .toList();
    }

    private AgentStep taskStage(String stage, int attempt) {
        return taskStages().stream()
                .filter(step -> stage.equals(step.getDecisionCode()) && step.getAttempt() == attempt)
                .findFirst().orElseThrow();
    }

    private AgentTask task(int attempt) {
        AgentTask task = new AgentTask();
        task.setTaskId("task-1");
        task.setRunId("run-1");
        task.setCurrentStage("READING");
        task.setAttemptCount(attempt);
        task.setRequestJson("{\"assets\":[{\"assetId\":\"asset-1\"}]}");
        return task;
    }

    private AgentStep step(String id, int order, String type, String decisionCode, int attempt) {
        AgentStep step = new AgentStep();
        step.setStepId(id);
        step.setRunId("run-1");
        step.setStepOrder(order);
        step.setStepType(type);
        step.setAttempt(attempt);
        step.setStatus("COMPLETED");
        step.setDecisionCode(decisionCode);
        step.setInputSummaryJson("{}");
        step.setOutputSummaryJson("{}");
        step.setCreatedAt(1_000L + order);
        return step;
    }
}
