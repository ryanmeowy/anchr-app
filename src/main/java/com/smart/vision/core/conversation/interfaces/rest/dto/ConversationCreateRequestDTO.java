package com.smart.vision.core.conversation.interfaces.rest.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for creating conversation session.
 */
@Data
public class ConversationCreateRequestDTO {

    @Size(max = 128, message = "title length cannot exceed 128")
    private String title;
}

