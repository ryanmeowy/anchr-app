package com.anchr.core.ingestion.interfaces.rest;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateItemDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeBaseIngestionControllerTest {

    @Test
    void createTask_shouldReturn201ForCreationAnd200ForReplay() {
        StubIngestionService service = new StubIngestionService();
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(service);
        IngestionTask task = task("task-1", "request-1");
        IngestionTaskCreateRequestDTO request = request("request-1");

        service.createResult = new IngestionApplicationService.IngestionTaskCreateResult(task, true);
        var created = controller.createTask("kb-1", request);
        service.createResult = new IngestionApplicationService.IngestionTaskCreateResult(task, false);
        var replay = controller.createTask("kb-1", request);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getCode()).isEqualTo(201);
        assertThat(created.getBody().getData().getTaskId()).isEqualTo("task-1");
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().getCode()).isEqualTo(200);
        assertThat(service.lastCommand.clientRequestId()).isEqualTo("request-1");
    }

    @Test
    void createTask_shouldKeepClientRequestIdOptionalForLegacyClients() {
        StubIngestionService service = new StubIngestionService();
        service.createResult = new IngestionApplicationService.IngestionTaskCreateResult(task("task-1", null), true);
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(service);

        var response = controller.createTask("kb-1", request(null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(service.lastCommand.clientRequestId()).isNull();
    }

    @Test
    void lookupByClientRequestId_shouldDisableCaching() {
        StubIngestionService service = new StubIngestionService();
        service.lookupResult = task("task-1", "request-1");
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(service);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        var response = controller.getTaskByClientRequestId("kb-1", "request-1", servletResponse);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(servletResponse.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getClientRequestId()).isEqualTo("request-1");
        assertThat(service.lookupKbId).isEqualTo("kb-1");
        assertThat(service.lookupClientRequestId).isEqualTo("request-1");
    }

    @Test
    void lookupByClientRequestId_shouldDisableCachingBeforeARecoverable404() {
        StubIngestionService service = new StubIngestionService();
        service.lookupFailure = new BusinessException(ApiError.INGESTION_TASK_NOT_FOUND);
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(service);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.getTaskByClientRequestId("kb-1", "request-1", servletResponse));

        assertThat(failure.getError()).isEqualTo(ApiError.INGESTION_TASK_NOT_FOUND);
        assertThat(servletResponse.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void retryItem_shouldPassAllPathVariablesAndReturnUpdatedTask() {
        StubIngestionService service = new StubIngestionService();
        service.operationResult = task("task-retry-one", null).toBuilder()
                .kbId("kb-write")
                .status(IngestionTaskStatus.RUNNING)
                .runningCount(1)
                .build();
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(service);

        var response = controller.retryItem("kb-write", "task-failed", "item-failed");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("Success");
        assertThat(response.getData().getTaskId()).isEqualTo("task-retry-one");
        assertThat(response.getData().getKbId()).isEqualTo("kb-write");
        assertThat(response.getData().getStatus()).isEqualTo("RUNNING");
        assertThat(service.lastOperation).isEqualTo("retryItem");
        assertThat(service.lastArguments)
                .containsExactly("kb-write", "task-failed", "item-failed");
    }

    @Test
    void retryFailed_shouldPassTaskScopeAndReturnUpdatedTask() {
        StubIngestionService service = new StubIngestionService();
        service.operationResult = task("task-retry-all", null).toBuilder()
                .kbId("kb-write")
                .status(IngestionTaskStatus.PENDING)
                .build();
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(service);

        var response = controller.retryFailed("kb-write", "task-failed");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().getTaskId()).isEqualTo("task-retry-all");
        assertThat(response.getData().getKbId()).isEqualTo("kb-write");
        assertThat(response.getData().getStatus()).isEqualTo("PENDING");
        assertThat(service.lastOperation).isEqualTo("retryFailed");
        assertThat(service.lastArguments).containsExactly("kb-write", "task-failed");
    }

    @Test
    void maintenanceEndpoints_shouldPassAssetScopeAndReturnMaintenanceTaskContract() {
        StubIngestionService service = new StubIngestionService();
        service.operationResult = task("task-reparse", null).toBuilder()
                .sourceType(IngestionSourceType.REPARSE)
                .build();
        KnowledgeBaseIngestionController controller = new KnowledgeBaseIngestionController(service);

        var reparse = controller.reparse("kb-write", "asset-parse");

        assertThat(reparse.getCode()).isEqualTo(200);
        assertThat(reparse.getData().getTaskId()).isEqualTo("task-reparse");
        assertThat(reparse.getData().getAssetId()).isEqualTo("asset-parse");
        assertThat(reparse.getData().getStatus()).isEqualTo("PENDING");
        assertThat(service.lastOperation).isEqualTo("reparse");
        assertThat(service.lastArguments).containsExactly("kb-write", "asset-parse");

        service.operationResult = task("task-reembed", null).toBuilder()
                .sourceType(IngestionSourceType.REEMBED)
                .build();
        var reembed = controller.reembed("kb-write", "asset-embed");

        assertThat(reembed.getCode()).isEqualTo(200);
        assertThat(reembed.getData().getTaskId()).isEqualTo("task-reembed");
        assertThat(reembed.getData().getAssetId()).isEqualTo("asset-embed");
        assertThat(reembed.getData().getStatus()).isEqualTo("PENDING");
        assertThat(service.lastOperation).isEqualTo("reembed");
        assertThat(service.lastArguments).containsExactly("kb-write", "asset-embed");
    }

    @Test
    void mutationEndpoints_shouldRemainPostOnlyAndExcludeGuestRole() throws Exception {
        assertMutationContract(
                "retryFailed",
                new Class<?>[]{String.class, String.class},
                "/ingestion-tasks/{taskId}/retry-failed");
        assertMutationContract(
                "retryItem",
                new Class<?>[]{String.class, String.class, String.class},
                "/ingestion-tasks/{taskId}/items/{itemId}/retry");
        assertMutationContract(
                "reparse",
                new Class<?>[]{String.class, String.class},
                "/documents/{assetId}/reparse");
        assertMutationContract(
                "reembed",
                new Class<?>[]{String.class, String.class},
                "/documents/{assetId}/reembed");
    }

    private void assertMutationContract(
            String methodName, Class<?>[] parameterTypes, String path) throws Exception {
        var method = KnowledgeBaseIngestionController.class
                .getDeclaredMethod(methodName, parameterTypes);
        RequireAuth requireAuth = method.getAnnotation(RequireAuth.class);
        PostMapping postMapping = method.getAnnotation(PostMapping.class);

        assertThat(requireAuth).isNotNull();
        assertThat(requireAuth.roles()).containsExactlyInAnyOrder("ADMIN", "USER");
        assertThat(postMapping).isNotNull();
        assertThat(postMapping.value().length == 0 ? postMapping.path() : postMapping.value())
                .containsExactly(path);
    }

    private IngestionTaskCreateRequestDTO request(String clientRequestId) {
        IngestionTaskCreateItemDTO item = new IngestionTaskCreateItemDTO();
        item.setFileName("mysql.pdf");
        item.setTitle("MySQL");
        item.setFileType("PDF");
        item.setMimeType("application/pdf");
        item.setSizeBytes(1024L);
        item.setObjectKey("objects/mysql.pdf");
        item.setFileHash("hash-a");
        IngestionTaskCreateRequestDTO request = new IngestionTaskCreateRequestDTO();
        request.setClientRequestId(clientRequestId);
        request.setItems(List.of(item));
        return request;
    }

    private IngestionTask task(String taskId, String clientRequestId) {
        return IngestionTask.builder()
                .id(taskId)
                .kbId("kb-1")
                .sourceType(IngestionSourceType.UPLOAD)
                .clientRequestId(clientRequestId)
                .requestHash(clientRequestId == null ? null : "v1:hash")
                .status(IngestionTaskStatus.PENDING)
                .items(List.of())
                .build();
    }

    private static final class StubIngestionService implements IngestionApplicationService {

        private IngestionTaskCreateResult createResult;
        private IngestionTask lookupResult;
        private BusinessException lookupFailure;
        private IngestionTask operationResult;
        private IngestionCreateCommand lastCommand;
        private String lookupKbId;
        private String lookupClientRequestId;
        private String lastOperation;
        private List<String> lastArguments;

        @Override
        public IngestionTaskCreateResult createTask(String kbId, IngestionCreateCommand command) {
            lastCommand = command;
            return createResult;
        }

        @Override
        public IngestionTask getTaskByClientRequestId(String kbId, String clientRequestId) {
            lookupKbId = kbId;
            lookupClientRequestId = clientRequestId;
            if (lookupFailure != null) {
                throw lookupFailure;
            }
            return lookupResult;
        }

        @Override
        public List<IngestionTask> listTasks(String kbId, IngestionTaskStatus status, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IngestionTask getTask(String kbId, String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IngestionTask retryItem(String kbId, String taskId, String itemId) {
            record("retryItem", kbId, taskId, itemId);
            return operationResult;
        }

        @Override
        public IngestionTask retryFailed(String kbId, String taskId) {
            record("retryFailed", kbId, taskId);
            return operationResult;
        }

        @Override
        public IngestionTask createReparseTask(String kbId, String assetId) {
            record("reparse", kbId, assetId);
            return operationResult;
        }

        @Override
        public IngestionTask createReembedTask(String kbId, String assetId) {
            record("reembed", kbId, assetId);
            return operationResult;
        }

        private void record(String operation, String... arguments) {
            lastOperation = operation;
            lastArguments = List.of(arguments);
        }
    }
}
