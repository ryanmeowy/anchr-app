package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.application.IngestionApplicationService;
import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.IngestionTaskProcessor;
import com.anchr.core.ingestion.application.acl.IngestionActivityAcl;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import com.anchr.core.kb.application.KnowledgeBaseService;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * The demo deployment caps one ingestion request to a single file. The cap has to be enforced by
 * the service, not only declared in the capabilities response, otherwise direct API calls bypass
 * the front-end check.
 */
@ExtendWith(MockitoExtension.class)
class IngestionUploadLimitDemoTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private IngestionTaskRepository ingestionTaskRepository;
    @Mock
    private IdGen idGen;
    @Mock
    private IngestionActivityAcl activityEventService;
    @Mock
    private IngestionTaskProcessor ingestionTaskProcessor;
    @Mock
    private IngestionCreateTransactionRunner transactionRunner;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("demo_visitor", "USER"));
        lenient().when(transactionRunner.read(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        lenient().when(idGen.nextIdStr()).thenReturn("1000");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void demoDeploymentShouldRejectTwoFilesInOneRequest() {
        IngestionTaskCreateUseCase useCase = useCaseFor(demoProperties());

        assertThatThrownBy(() -> useCase.create("kb-1", commandWithFiles(2)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getError())
                .isEqualTo(ApiError.INVALID_REQUEST);
    }

    @Test
    void demoDeploymentShouldAcceptASingleFile() {
        IngestionTaskCreateUseCase useCase = useCaseFor(demoProperties());
        lenient().when(assetRepository.findActiveByHash(anyString(), any()))
                .thenReturn(java.util.Optional.empty());

        // The new limit check must not reject a legal one-file request.
        assertThatCode(() -> useCase.create("kb-1", commandWithFiles(1)))
                .doesNotThrowAnyException();
    }

    private DemoProperties demoProperties() {
        return new DemoProperties(true, 5, 8, 80, 50, 10, 1, false, "", "", "cn-shanghai", "", "", false);
    }

    private IngestionTaskCreateUseCase useCaseFor(DemoProperties properties) {
        IngestionCapabilityService capabilityService = new IngestionCapabilityService(properties);
        IngestionTaskQuery taskQuery = new IngestionTaskQuery(knowledgeBaseService,
                ingestionTaskRepository);
        return new IngestionTaskCreateUseCase(
                knowledgeBaseService,
                assetRepository,
                knowledgeBaseRepository,
                ingestionTaskRepository,
                capabilityService,
                idGen,
                activityEventService,
                ingestionTaskProcessor,
                transactionRunner,
                taskQuery);
    }

    private IngestionApplicationService.IngestionCreateCommand commandWithFiles(int count) {
        List<IngestionApplicationService.IngestionCreateItemCommand> items =
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(index -> new IngestionApplicationService.IngestionCreateItemCommand(
                                "doc-" + index + ".pdf",
                                "Doc " + index,
                                "PDF",
                                "application/pdf",
                                1024L,
                                "objects/doc-" + index + ".pdf",
                                "hash-" + index))
                        .toList();
        return new IngestionApplicationService.IngestionCreateCommand(
                null, IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, items);
    }
}
