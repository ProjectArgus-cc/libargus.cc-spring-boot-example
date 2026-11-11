package cc.projectargus.workbench.web.dto;

import java.util.List;

/**
 * Request payload for {@code POST /api/chat} and {@code POST /api/chat/stream}.
 *
 * <p>Callers may supply either a multi-turn {@code messages} list or a single-turn {@code prompt}
 * convenience field.
 */
public record ChatRequestDto(
    List<ChatMessageDto> messages,
    String prompt,
    Float temperature,
    Integer maxOutputTokens,
    String requestId
) {}
