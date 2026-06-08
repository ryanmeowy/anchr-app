package com.anchr.core.kb.application.ingestion;

import com.anchr.core.kb.domain.model.ingestion.DedupeStrategy;
import com.anchr.core.kb.domain.model.ingestion.IngestionStage;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Provides ingestion capability declarations for frontend import pages.
 */
@Service
public class IngestionCapabilityService {

    private static final long MAX_FILE_SIZE_BYTES = 209_715_200L;
    private static final int MAX_FILES_PER_BATCH = 50;

    public IngestionCapabilities getCapabilities() {
        return IngestionCapabilities.builder()
                .supportedFormats(List.of(
                        SupportedFormat.of("PDF", List.of("pdf"), List.of("application/pdf"), true, "P0"),
                        SupportedFormat.of("TXT", List.of("txt"), List.of("text/plain"), true, "P0"),
                        SupportedFormat.of("MD", List.of("md", "markdown"), List.of("text/markdown", "text/x-markdown"), true, "P0"),
                        SupportedFormat.of("DOCX", List.of("docx"),
                                List.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                                true, "P1"),
                        SupportedFormat.of("XLSX", List.of("xlsx", "xls"),
                                List.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/vnd.ms-excel"),
                                true, "P1"),
                        SupportedFormat.of("CSV", List.of("csv"), List.of("text/csv", "application/csv"), true, "P1"),
                        SupportedFormat.of("HTML", List.of("html", "htm"),
                                List.of("text/html", "application/xhtml+xml"), true, "P1"),
                        SupportedFormat.of("URL", List.of(), List.of("text/html"), true, "P1"),
                        SupportedFormat.of("PPTX", List.of("pptx"),
                                List.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                                true, "P2"),
                        SupportedFormat.of("ZIP", List.of("zip"),
                                List.of("application/zip", "application/x-zip-compressed"), true, "P2"),
                        SupportedFormat.of("IMAGE", List.of("png", "jpg", "jpeg", "webp"),
                                List.of("image/png", "image/jpeg", "image/webp"), true, "P0")
                ))
                .maxFileSizeBytes(MAX_FILE_SIZE_BYTES)
                .maxFilesPerBatch(MAX_FILES_PER_BATCH)
                .dedupeStrategies(List.of(DedupeStrategy.SKIP, DedupeStrategy.OVERWRITE, DedupeStrategy.VERSIONED))
                .defaultDedupeStrategy(DedupeStrategy.SKIP)
                .ingestionStages(List.of(IngestionStage.UPLOAD, IngestionStage.PARSE, IngestionStage.CHUNK,
                        IngestionStage.EMBED, IngestionStage.INDEX, IngestionStage.ASKABLE))
                .build();
    }

    public boolean isSupportedFileType(String fileType) {
        return getCapabilities().getSupportedFormats().stream()
                .anyMatch(format -> format.getFileType().equalsIgnoreCase(fileType) && format.isEnabled());
    }

    @Value
    @Builder
    public static class IngestionCapabilities {
        List<SupportedFormat> supportedFormats;
        long maxFileSizeBytes;
        int maxFilesPerBatch;
        List<DedupeStrategy> dedupeStrategies;
        DedupeStrategy defaultDedupeStrategy;
        List<IngestionStage> ingestionStages;
    }

    @Value
    @Builder
    public static class SupportedFormat {
        String fileType;
        List<String> extensions;
        List<String> mimeTypes;
        boolean enabled;
        String priority;

        public static SupportedFormat of(String fileType, List<String> extensions, List<String> mimeTypes,
                                         boolean enabled, String priority) {
            return SupportedFormat.builder()
                    .fileType(fileType)
                    .extensions(extensions)
                    .mimeTypes(mimeTypes)
                    .enabled(enabled)
                    .priority(priority)
                    .build();
        }
    }
}
