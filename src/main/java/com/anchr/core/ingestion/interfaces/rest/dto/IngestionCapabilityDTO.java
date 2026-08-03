package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.application.IngestionCapabilityService;
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
    long maxImageFileSizeBytes;
    int maxFilesPerBatch;
    List<String> dedupeStrategies;
    String defaultDedupeStrategy;
    List<String> ingestionStages;

    public static IngestionCapabilityDTO from(IngestionCapabilityService.IngestionCapabilities capabilities) {
        return IngestionCapabilityDTO.builder()
                .supportedFormats(capabilities.getSupportedFormats().stream().map(SupportedFormatDTO::from).toList())
                .maxFileSizeBytes(capabilities.getMaxFileSizeBytes())
                .maxImageFileSizeBytes(capabilities.getMaxImageFileSizeBytes())
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
