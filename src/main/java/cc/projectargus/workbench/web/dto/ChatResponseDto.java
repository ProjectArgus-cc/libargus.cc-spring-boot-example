package cc.projectargus.workbench.web.dto;

/**
 * Synchronous chat response returned by {@code POST /api/chat}.
 */
public record ChatResponseDto(
    String requestId,
    String reply,
    String finishReason,
    TokenUsageDto usage,
    int evictedMessages,
    long durationMs
) {}
