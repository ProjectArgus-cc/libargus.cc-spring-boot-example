package cc.projectargus.workbench.web.dto;

import java.util.List;

/**
 * Response payload for {@code GET /api/status}.
 *
 * <p>Exposes readiness, active binding, and bounded configuration limits without leaking
 * filesystem paths, environment variables, or internal pointers.
 */
public record StatusResponseDto(
    String status,
    boolean chatEnabled,
    String binding,
    String runtimeVersion,
    List<String> availableBackends,
    String modelName,
    String chatFormatter,
    int contextLength,
    int maxOutputTokens,
    int gpuLayers,
    int maxConcurrentInference,
    int activeInferenceCount,
    int maxPromptChars,
    int maxHistoryMessages,
    String errorCode,
    String errorMessage
) {}
