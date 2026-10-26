package com.anchr.core.ingestion.application;

import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.ingestion.application.constant.IngestionConstant;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionStage;
import com.anchr.core.search.domain.model.AssetType;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

/**
 * Provides ingestion capability declarations for frontend import pages.
 */
@Service
public class IngestionCapabilityService {

    private final DemoProperties demoProperties;

    public IngestionCapabilityService(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    public IngestionCapabilities getCapabilities() {
        return IngestionCapabilities.builder()
                .supportedFormats(Stream.of(AssetType.PDF, AssetType.TXT, AssetType.IMAGE, AssetType.MARKDOWN).map(SupportedFormat::of).toList())
                .maxFileSizeBytes(IngestionConstant.MAX_FILE_SIZE_BYTES)
                .maxImageFileSizeBytes(IngestionConstant.MAX_IMAGE_FILE_SIZE_BYTES)
                .maxFilesPerBatch(maxFilesPerBatch())
                .dedupeStrategies(List.of(DedupeStrategy.SKIP, DedupeStrategy.OVERWRITE, DedupeStrategy.VERSIONED))
                .defaultDedupeStrategy(DedupeStrategy.SKIP)
                .ingestionStages(List.of(IngestionStage.UPLOAD, IngestionStage.PARSE, IngestionStage.CHUNK,
                        IngestionStage.EMBED, IngestionStage.INDEX, IngestionStage.ASKABLE))
                .build();
    }

    /**
     * Files allowed in one ingestion request. The demo deployment restricts it to keep a single
     * visitor from occupying the shared parsing queue.
     */
    public int maxFilesPerBatch() {
        return demoProperties.enabled()
                ? demoProperties.uploadMaxFiles()
                : IngestionConstant.MAX_FILES_PER_BATCH;
    }

    public boolean isSupportedFileType(String fileType) {
        return getCapabilities().getSupportedFormats().stream()
                .anyMatch(format -> format.getFileType().equalsIgnoreCase(fileType) && format.isEnabled());
    }

    public long maxFileSizeBytesFor(String fileType) {
        return AssetType.IMAGE.getFileType().equalsIgnoreCase(fileType)
                ? IngestionConstant.MAX_IMAGE_FILE_SIZE_BYTES
                : IngestionConstant.MAX_FILE_SIZE_BYTES;
    }

    @Value
    @Builder
    public static class IngestionCapabilities {
        List<SupportedFormat> supportedFormats;
        long maxFileSizeBytes;
        long maxImageFileSizeBytes;
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

        public static SupportedFormat of(AssetType type) {
            return SupportedFormat.builder()
                    .fileType(type.getFileType())
                    .extensions(type.getExtensions())
                    .mimeTypes(type.getMimeTypes())
                    .enabled(type.isEnabled())
                    .priority(type.getPriority())
                    .build();
        }
    }
}
