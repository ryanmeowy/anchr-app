package com.anchr.core.conversation.application.agent;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.conversation.application.assembler.ConversationCitationMapper;
import com.anchr.core.conversation.domain.port.AgentModelPort;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.domain.repository.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentWorkflowSpringWiringTest {

    @Test
    void workflowReceivesItsExternalCollaboratorsFromSpring() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RuntimeConfigUnit.class, () -> mock(RuntimeConfigUnit.class));
            context.registerBean(AgentModelPort.class, () -> mock(AgentModelPort.class));
            context.registerBean(ConversationGenerationPort.class,
                    () -> mock(ConversationGenerationPort.class));
            context.registerBean(AgentToolRegistry.class, () -> mock(AgentToolRegistry.class));
            context.registerBean(AgentToolExecutor.class, () -> mock(AgentToolExecutor.class));
            context.registerBean(ConversationRepository.class, () -> mock(ConversationRepository.class));
            context.registerBean(AgentRequestContextResolver.class,
                    () -> mock(AgentRequestContextResolver.class));
            context.registerBean(ConversationCitationMapper.class, ConversationCitationMapper::new);
            context.registerBean(AgentTraceRecorder.class, () -> mock(AgentTraceRecorder.class));
            context.registerBean(AgentRunCancellationRegistry.class,
                    () -> mock(AgentRunCancellationRegistry.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(MeterRegistry.class, () -> new SimpleMeterRegistry());
            context.register(AgentActionProtocol.class, AgentEvidenceFinalizer.class,
                    AgentFinalPresentation.class, AgentCitationPolicy.class,
                    AgentAnswerVerifier.class, AgentWorkflowImpl.class);
            context.refresh();

            AgentWorkflowImpl workflow = context.getBean(AgentWorkflowImpl.class);
            assertThat(ReflectionTestUtils.getField(workflow, "actionProtocol"))
                    .isSameAs(context.getBean(AgentActionProtocol.class));
            assertThat(ReflectionTestUtils.getField(workflow, "evidenceFinalizer"))
                    .isSameAs(context.getBean(AgentEvidenceFinalizer.class));
            assertThat(ReflectionTestUtils.getField(workflow, "finalPresentation"))
                    .isSameAs(context.getBean(AgentFinalPresentation.class));
            assertThat(ReflectionTestUtils.getField(workflow, "answerVerifier"))
                    .isSameAs(context.getBean(AgentAnswerVerifier.class));
        }
    }
}
