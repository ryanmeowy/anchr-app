package com.anchr.core.activity.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Recent question item.
 */
@Value
@Builder
public class RecentQuestionDTO {

    String turnId;
    String sessionId;
    String question;
    List<String> kbScope;
    LocalDateTime createdAt;
}
