package cc.projectargus.workbench.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Spring Boot {@link org.springframework.boot.diagnostics.FailureAnalyzer} that renders a clean,
 * actionable startup diagnostic when {@code ARGUS_CHAT_MODEL_PATH} is missing, invalid, or incompatible
 * with the active native runtime.
 */
public final class ArgusStartupFailureAnalyzer extends AbstractFailureAnalyzer<ArgusStartupFailureException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ArgusStartupFailureException cause) {
        String description = String.format(
            "Local AI Workbench failed to initialize [%s]: %s",
            cause.getErrorCode(),
            cause.getMessage()
        );
        return new FailureAnalysis(description, cause.getRemediation(), cause);
    }
}
