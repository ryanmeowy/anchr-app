package com.smart.vision.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Paged conversation session list response.
 */
@Data
public class ConversationSessionListDTO implements Serializable {

    private List<ConversationSessionDTO> items = new ArrayList<>();
    private String nextCursor;
}
