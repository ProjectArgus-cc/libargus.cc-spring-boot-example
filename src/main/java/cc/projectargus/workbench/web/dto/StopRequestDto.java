package cc.projectargus.workbench.web.dto;

/**
 * Request payload for {@code POST /api/chat/stop}.
 */
public record StopRequestDto(
    String requestId
) {}
