package com.anchr.core.conversation.application.model;

public record ChatResponseResult(String answer, AnswerStatus answerStatus, String fallbackReason) {
}
