package com.anchr.core.search.interfaces.rest.dto;

import lombok.Data;

@Data
public class IndexRollbackRequest {
    private String physicalIndex;
}
