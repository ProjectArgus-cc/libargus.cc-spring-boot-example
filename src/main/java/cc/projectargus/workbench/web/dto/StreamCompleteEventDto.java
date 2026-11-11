package cc.projectargus.workbench.web.dto;

/**
 * Terminal completion event ({@code event: complete}) emitted on {@code POST /api/chat/stream}.
 */
public record StreamCompleteEventDto(
    String requestId,
    String text,
    String finishReason,
    boolean cancelled,
    TokenUsageDto usage,
    int evictedMessages,
    long durationMs
) {}
