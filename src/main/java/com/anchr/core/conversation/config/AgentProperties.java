package com.anchr.core.conversation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {
    private boolean enabled = false;
    private String workflowVersion = "general-agent-v1";
    private ToolCallMode toolCallMode = ToolCallMode.AUTO;
    private NativeToolChoice nativeToolChoice = NativeToolChoice.REQUIRED;
    private boolean fallbackToTraditional = true;
    private int maxSteps = 12;
    private int maxToolCalls = 8;
    private Duration totalTimeout = Duration.ofSeconds(90);
    private Duration modelTimeout = Duration.ofSeconds(30);
    private Duration taskTimeout = Duration.ofMinutes(10);
    private Duration taskModelTimeout = Duration.ofSeconds(90);
    private Duration taskLease = Duration.ofMinutes(2);
    private int taskMaxRetries = 2;
    private int summaryMaxDocuments = 3;
    private int summaryMaxSegments = 500;
    private int summaryMaxChars = 500_000;
    private int summaryBatchChars = 12_000;

    public enum ToolCallMode {
        NATIVE, JSON, AUTO
    }

    public enum NativeToolChoice {
        AUTO, REQUIRED
    }
}
