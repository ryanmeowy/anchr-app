package com.smart.vision.core.conversation.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for renaming one conversation session.
 */
@Data
public class ConversationRenameRequestDTO {

    @NotBlank(message = "title cannot be empty")
    @Size(max = 128, message = "title length cannot exceed 128")
    private String title;
}
