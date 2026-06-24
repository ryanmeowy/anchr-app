package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Data;

@Data
public class KbQueryRequestDTO {
    private String keyword;
    private String status;
    private String updateAfter;
    private String updateBefore;
    private Integer page;
    private Integer size;
}
