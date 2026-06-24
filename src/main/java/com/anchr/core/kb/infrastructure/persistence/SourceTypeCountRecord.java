package com.anchr.core.kb.infrastructure.persistence;

import lombok.Data;

/**
 * Persistence record for the per-file-type asset count query.
 */
@Data
public class SourceTypeCountRecord {

    private String fileType;
    private Long count;
}
