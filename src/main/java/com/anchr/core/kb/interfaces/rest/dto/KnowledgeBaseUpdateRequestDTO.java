package com.anchr.core.kb.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for updating a knowledge base.
 */
@Data
public class KnowledgeBaseUpdateRequestDTO {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 4096)
    private String description;
}
