package com.anchr.core.conversation.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for conversation message.
 */
@Data
public class ConversationMessageRequestDTO {

    @NotBlank(message = "query cannot be empty")
    @Size(max = 1000, message = "query length cannot exceed 1000")
    private String query;

    @Min(value = 1, message = "limit must be greater than 0")
    @Max(value = 200, message = "limit cannot exceed 200")
    private Integer limit;

    @Size(max = 100, message = "kbIds cannot exceed 100")
    private List<String> kbIds;

    @Size(max = 100, message = "assetIdList cannot exceed 100")
    private List<String> assetIdList;

    @Size(max = 32, message = "answerMode length cannot exceed 32")
    private String answerMode;

    @Size(max = 10, message = "preferredModalities cannot exceed 10")
    private List<@Pattern(regexp = "(?i)TEXT|IMAGE|MIXED", message = "preferredModalities only supports TEXT, IMAGE, or MIXED") String> preferredModalities;

    private Boolean debug;

    private Boolean stream;

    /** Client preference; the server-side feature flag must also be enabled. */
    private Boolean agentEnabled;
}
