package cc.projectargus.workbench.web.dto;

/**
 * Standardized JSON error payload returned by all REST and SSE endpoints.
 */
public record ApiErrorDto(
    String code,
    String message
) {}
