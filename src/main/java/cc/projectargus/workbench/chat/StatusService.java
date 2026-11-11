package cc.projectargus.workbench.chat;

import cc.projectargus.workbench.config.ArgusWorkbenchProperties;
import cc.projectargus.workbench.runtime.InferenceConcurrencyGate;
import cc.projectargus.workbench.runtime.InferenceGateway;
import cc.projectargus.workbench.runtime.RuntimeStatusSnapshot;
import cc.projectargus.workbench.web.dto.StatusResponseDto;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Builds sanitized runtime status responses for {@code GET /api/status}.
 */
@Service
public class StatusService {

    private final InferenceGateway inferenceGateway;
    private final InferenceConcurrencyGate concurrencyGate;
    private final ArgusWorkbenchProperties properties;

    public StatusService(
        InferenceGateway inferenceGateway,
        InferenceConcurrencyGate concurrencyGate,
        ArgusWorkbenchProperties properties
    ) {
        this.inferenceGateway = Objects.requireNonNull(inferenceGateway, "inferenceGateway");
        this.concurrencyGate = Objects.requireNonNull(concurrencyGate, "concurrencyGate");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public StatusResponseDto getStatus() {
        RuntimeStatusSnapshot snapshot = inferenceGateway.getStatusSnapshot(
            concurrencyGate.getActiveInferenceCount()
        );
        return new StatusResponseDto(
            snapshot.status(),
            snapshot.chatEnabled(),
            snapshot.binding(),
            snapshot.runtimeVersion(),
            snapshot.availableBackends(),
            snapshot.modelName(),
            snapshot.chatFormatter(),
            snapshot.contextLength(),
            snapshot.maxOutputTokens(),
            snapshot.gpuLayers(),
            snapshot.maxConcurrentInference(),
            snapshot.activeInferenceCount(),
            properties.getMaxPromptChars(),
            properties.getMaxHistoryMessages(),
            snapshot.errorCode(),
            snapshot.errorMessage()
        );
    }
}
