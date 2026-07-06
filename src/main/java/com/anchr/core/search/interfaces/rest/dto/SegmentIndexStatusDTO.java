package com.anchr.core.search.interfaces.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentIndexStatusDTO {

    private SegmentIndexStatus status;           // NOT_READY | INITIALIZING | READY | REBUILDING
    private boolean indexExists;
    private boolean readable;
    private boolean writable;
    private Integer actualDim;       // from ES mapping
    private String actualModel;      // from ES mapping _meta
    private String actualProfileFingerprint;
    private Integer expectedDim;     // from active capability config
    private String expectedModel;    // from active capability config
    private String expectedProfileFingerprint;
    private PendingRebuild pendingRebuild;
    private RebuildProgress rebuildProgress;
    private String lastError;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingRebuild {
        private String taskId;
        private int expectedDim;
        private String reason;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RebuildProgress {
        private long migrated;   // 已迁移文档数
        private long total;      // 旧索引文档总数
        private String phase;    // MIGRATING | SWITCHING_ALIAS | COMPLETED
    }
}
