package com.anchr.core.conversation.interfaces.rest.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for creating conversation session.
 */
@Data
public class ConversationCreateRequestDTO {

    @Size(max = 128, message = "title length cannot exceed 128")
    private String title;

    @Size(max = 100, message = "kbIds cannot exceed 100")
    private List<String> kbIds;

    @Size(max = 100, message = "assetIdList cannot exceed 100")
    private List<String> assetIdList;
}
