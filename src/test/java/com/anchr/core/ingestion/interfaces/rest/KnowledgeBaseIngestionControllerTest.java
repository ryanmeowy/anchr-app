package com.anchr.core.ingestion.interfaces.rest;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateItemDTO;
import com.anchr.core.ingestion.interfaces.rest.dto.IngestionTaskCreateRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

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
        private IngestionCreateCommand lastCommand;
        private String lookupKbId;
        private String lookupClientRequestId;

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
            throw new UnsupportedOperationException();
        }

        @Override
        public IngestionTask retryFailed(String kbId, String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IngestionTask createReparseTask(String kbId, String assetId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IngestionTask createReembedTask(String kbId, String assetId) {
            throw new UnsupportedOperationException();
        }
    }
}
