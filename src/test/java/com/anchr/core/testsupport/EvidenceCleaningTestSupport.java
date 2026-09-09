package com.anchr.core.testsupport;

import com.anchr.core.conversation.application.impl.EvidenceCleaningService;
import com.anchr.core.conversation.application.model.*;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Existing behavior tests get a separate, deterministic checker; adversarial tests use explicit verdicts. */
public final class EvidenceCleaningTestSupport {
    private EvidenceCleaningTestSupport() {}
    public static EvidenceCleaningService allowing() {
        ObjectMapper mapper = new ObjectMapper();
        return new EvidenceCleaningService(new ConversationGenerationPort() {
            @Override public String generate(List<ConversationModelMessage> messages, GenerationOptions options) {
                try {
                    var input = mapper.readTree(messages.get(1).content());
                    var result = mapper.createArrayNode();
                    for (var p : input.path("paragraphs")) result.add(mapper.valueToTree(Map.of(
                            "id", p.path("id").asText(), "decision", "KEEP", "reason", "ordinary source text")));
                    return mapper.writeValueAsString(Map.of("decisions", result));
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
        }, mapper);
    }
}
