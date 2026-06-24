package com.anchr.core.kb.interfaces.rest.dto;

import com.anchr.core.kb.domain.model.KnowledgeBaseHealth;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * KB health report response DTO.
 */
@Value
@Builder
public class KnowledgeBaseHealthDTO {

    String kbId;
    String kbName;
    String status;
    int score;
    DocumentHealthDTO documents;
    SegmentHealthDTO segments;
    List<SourceTypeHealthDTO> sourceTypes;

    public static KnowledgeBaseHealthDTO from(KnowledgeBaseHealth health) {
        return KnowledgeBaseHealthDTO.builder()
                .kbId(health.getKbId())
                .kbName(health.getKbName())
                .status(health.getStatus())
                .score(health.getScore())
                .documents(DocumentHealthDTO.from(health.getDocuments()))
                .segments(SegmentHealthDTO.from(health.getSegments()))
                .sourceTypes(health.getSourceTypes().stream()
                        .map(SourceTypeHealthDTO::from)
                        .toList())
                .build();
    }

    @Value
    @Builder
    public static class DocumentHealthDTO {
        int total;
        int indexed;
        int pending;
        int failed;

        public static DocumentHealthDTO from(KnowledgeBaseHealth.DocumentHealth d) {
            return DocumentHealthDTO.builder()
                    .total(d.getTotal())
                    .indexed(d.getIndexed())
                    .pending(d.getPending())
                    .failed(d.getFailed())
                    .build();
        }
    }

    @Value
    @Builder
    public static class SegmentHealthDTO {
        int total;
        int indexed;

        public static SegmentHealthDTO from(KnowledgeBaseHealth.SegmentHealth s) {
            return SegmentHealthDTO.builder()
                    .total(s.getTotal())
                    .indexed(s.getIndexed())
                    .build();
        }
    }

    @Value
    @Builder
    public static class SourceTypeHealthDTO {
        String type;
        String label;
        int count;
        int percentage;

        public static SourceTypeHealthDTO from(KnowledgeBaseHealth.SourceTypeHealth s) {
            return SourceTypeHealthDTO.builder()
                    .type(s.getType())
                    .label(s.getLabel())
                    .count(s.getCount())
                    .percentage(s.getPercentage())
                    .build();
        }
    }
}
