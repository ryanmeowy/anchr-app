package com.anchr.core.kb.interfaces.rest.dto.ingestion;

import com.anchr.core.kb.application.ingestion.IngestionCapabilityService;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Ingestion capabilities response DTO.
 */
@Value
@Builder
public class IngestionCapabilityDTO {

    List<SupportedFormatDTO> supportedFormats;
    long maxFileSizeBytes;
    int maxFilesPerBatch;
    List<String> dedupeStrategies;
    String defaultDedupeStrategy;
    List<String> ingestionStages;

    public static IngestionCapabilityDTO from(IngestionCapabilityService.IngestionCapabilities capabilities) {
        return IngestionCapabilityDTO.builder()
                .supportedFormats(capabilities.getSupportedFormats().stream().map(SupportedFormatDTO::from).toList())
                .maxFileSizeBytes(capabilities.getMaxFileSizeBytes())
                .maxFilesPerBatch(capabilities.getMaxFilesPerBatch())
                .dedupeStrategies(capabilities.getDedupeStrategies().stream().map(Enum::name).toList())
                .defaultDedupeStrategy(capabilities.getDefaultDedupeStrategy().name())
                .ingestionStages(capabilities.getIngestionStages().stream().map(Enum::name).toList())
                .build();
    }

    @Value
    @Builder
    public static class SupportedFormatDTO {
        String fileType;
        List<String> extensions;
        List<String> mimeTypes;
        boolean enabled;
        String priority;

        public static SupportedFormatDTO from(IngestionCapabilityService.SupportedFormat format) {
            return SupportedFormatDTO.builder()
                    .fileType(format.getFileType())
                    .extensions(format.getExtensions())
                    .mimeTypes(format.getMimeTypes())
                    .enabled(format.isEnabled())
                    .priority(format.getPriority())
                    .build();
        }
    }
}
