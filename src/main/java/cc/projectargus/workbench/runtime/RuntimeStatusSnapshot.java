package cc.projectargus.workbench.runtime;

import java.util.List;

/**
 * Sanitized point-in-time snapshot of the local AI runtime state.
 *
 * <p>Never contains absolute filesystem paths, environment variables, or native pointer addresses.
 */
public record RuntimeStatusSnapshot(
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
    String errorCode,
    String errorMessage
) {}
