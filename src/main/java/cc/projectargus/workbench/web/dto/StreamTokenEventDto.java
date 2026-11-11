package cc.projectargus.workbench.web.dto;

/**
 * Incremental token fragment event ({@code event: token}) emitted on {@code POST /api/chat/stream}.
 */
public record StreamTokenEventDto(
    String text
) {}
