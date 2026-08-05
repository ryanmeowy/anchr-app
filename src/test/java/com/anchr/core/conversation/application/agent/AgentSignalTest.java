package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.model.AgentTokenUsage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSignalTest {

    @Test
    void mapSnapshotsAllowNullValuesWithoutRetainingMutableSource() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("nullable", null);
        AgentSignal.Progress progress = new AgentSignal.Progress("stage", "message", 1, source);
        AgentSignal.Trace trace = new AgentSignal.Trace(1, AgentStepType.MODEL_DECISION, 1,
                "stop", source, source, AgentTokenUsage.EMPTY, 1, null);

        source.put("later", "mutation");

        assertThat(progress.details()).containsEntry("nullable", null).doesNotContainKey("later");
        assertThat(trace.inputSummary()).containsEntry("nullable", null).doesNotContainKey("later");
        assertThat(trace.outputSummary()).containsEntry("nullable", null).doesNotContainKey("later");
        assertThatThrownBy(() -> progress.details().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
