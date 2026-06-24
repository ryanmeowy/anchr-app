package com.anchr.core.kb.domain.model;

import lombok.Builder;
import lombok.Value;

/**
 * Read model for the KB health report.
 */
@Value
@Builder
public class KnowledgeBaseHealth {

    String kbId;
    String kbName;
    String status;
    int score;
    DocumentHealth documents;
    SegmentHealth segments;
    java.util.List<SourceTypeHealth> sourceTypes;

    @Value
    @Builder
    public static class DocumentHealth {
        int total;
        int indexed;
        int pending;
        int failed;
    }

    @Value
    @Builder
    public static class SegmentHealth {
        int total;
        int indexed;
    }

    @Value
    @Builder
    public static class SourceTypeHealth {
        String type;
        String label;
        int count;
        int percentage;
    }
}
