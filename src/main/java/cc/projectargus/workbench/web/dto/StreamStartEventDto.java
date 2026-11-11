package cc.projectargus.workbench.web.dto;

/**
 * Initial metadata event ({@code event: start}) emitted on {@code POST /api/chat/stream}
 * once the inference permit is acquired and token budgeting succeeds.
 */
public record StreamStartEventDto(
    String requestId,
    int promptTokens,
    int evictedMessages
) {}
