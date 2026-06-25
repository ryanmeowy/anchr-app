package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Data;

import java.util.List;

@Data
public class KbStatsRequestDTO {
    private List<String> kbIds;
}
