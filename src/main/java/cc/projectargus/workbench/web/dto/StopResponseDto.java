package cc.projectargus.workbench.web.dto;

/**
 * Response payload for {@code POST /api/chat/stop}.
 */
public record StopResponseDto(
    boolean stopped,
    String requestId
) {}
