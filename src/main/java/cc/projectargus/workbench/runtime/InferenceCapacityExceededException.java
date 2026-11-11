package cc.projectargus.workbench.runtime;

/**
 * Thrown when all configured inference permits ({@code ARGUS_MAX_CONCURRENT_INFERENCE}) are occupied.
 */
public final class InferenceCapacityExceededException extends RuntimeException {

    private final int maxConcurrentInference;

    public InferenceCapacityExceededException(int maxConcurrentInference) {
        super(
            "Local inference runtime is busy ("
            + maxConcurrentInference
            + " active generation(s) in progress). Wait for completion or cancel the active generation."
        );
        this.maxConcurrentInference = maxConcurrentInference;
    }

    public int getMaxConcurrentInference() {
        return maxConcurrentInference;
    }
}
