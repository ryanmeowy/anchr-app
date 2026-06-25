package com.anchr.core.settings.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Result of a storage connection test.
 */
@Value
@Builder
public class StorageConnectionTestResultDTO {
    boolean success;
    long latencyMs;
    String message;
}
