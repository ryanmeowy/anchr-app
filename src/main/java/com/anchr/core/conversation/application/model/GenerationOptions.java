package com.anchr.core.conversation.application.model;

import java.time.Duration;

public record GenerationOptions(Double temperature, Integer maxTokens, Duration timeout) {
}
