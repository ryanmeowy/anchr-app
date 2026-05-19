package com.smart.vision.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for conversation turn list API.
 */
@Data
public class ConversationTurnListDTO implements Serializable {

    private String sessionId;
    private List<ConversationTurnDTO> turns = new ArrayList<>();
}

