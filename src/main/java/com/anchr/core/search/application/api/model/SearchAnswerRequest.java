package com.anchr.core.search.application.api.model;

/** Input needed to build a grounded answer from already retrieved hits. */
public record SearchAnswerRequest(String question, String answerMode) {
}
