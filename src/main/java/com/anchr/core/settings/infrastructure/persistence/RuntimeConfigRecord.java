package com.anchr.core.settings.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuntimeConfigRecord {
    private String type;
    private String paramKey;
    private String paramValue;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
