package cc.projectargus.workbench.web.dto;

/**
 * Single conversation message submitted by an HTTP client.
 *
 * <p>Only {@code "user"} and {@code "assistant"} roles are accepted from clients; server-owned
 * system instructions cannot be overridden by client payloads.
 */
public record ChatMessageDto(
    String role,
    String content
) {}
