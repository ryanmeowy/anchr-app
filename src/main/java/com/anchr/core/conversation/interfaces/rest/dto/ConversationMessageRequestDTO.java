package com.anchr.core.conversation.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for conversation message.
 */
@Data
public class ConversationMessageRequestDTO {

    @NotBlank(message = "query cannot be empty")
    @Size(max = 200, message = "query length cannot exceed 200")
    private String query;

    @Min(value = 1, message = "limit must be greater than 0")
    @Max(value = 200, message = "limit cannot exceed 200")
    private Integer limit;

    @Size(max = 100, message = "kbIds cannot exceed 100")
    private List<String> kbIds;

    @Size(max = 32, message = "answerMode length cannot exceed 32")
    private String answerMode;

    @Size(max = 10, message = "preferredModalities cannot exceed 10")
    private List<String> preferredModalities;

    private Boolean debug;

    private Boolean stream;
}
