package cc.projectargus.workbench.web.dto;

/**
 * Token usage telemetry for a completed chat generation.
 */
public record TokenUsageDto(
    int inputTokens,
    int outputTokens,
    int totalTokens
) {}
