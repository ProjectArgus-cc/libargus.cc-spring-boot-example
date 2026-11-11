package cc.projectargus.workbench.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated externalized configuration properties for the Local AI Workbench.
 *
 * <p>All properties map directly to {@code ARGUS_*} environment variables via {@code application.yml}.
 */
@Validated
@ConfigurationProperties(prefix = "argus.chat")
public class ArgusWorkbenchProperties {

    /**
     * Path to a local chat/instruct GGUF model file ({@code ARGUS_CHAT_MODEL_PATH}).
     */
    private String modelPath = "";

    /**
     * Chat prompt template family ({@code ARGUS_CHAT_FORMATTER}).
     * Supported values: {@code auto}, {@code autoDetect}, {@code chatml}, {@code llama3},
     * {@code gemma}, {@code mistral}, {@code fallback}.
     */
    @NotBlank(message = "chatFormatter must not be blank")
    @Pattern(
        regexp = "(?i)^(auto|autodetect|chatml|llama3|gemma|mistral|fallback|fallbackinstruct)$",
        message = "chatFormatter must be one of: auto, chatml, llama3, gemma, mistral, fallback"
    )
    private String chatFormatter = "auto";

    /**
     * Inference context initialization mode ({@code ARGUS_CHAT_MODE}).
     * <ul>
     *   <li>{@code both} (default): initializes both {@code LibArgusChatModel} and {@code LibArgusStreamingChatModel}
     *       sharing model weights (two separate KV cache contexts).</li>
     *   <li>{@code stream}: initializes only {@code LibArgusStreamingChatModel} (single KV cache context).</li>
     *   <li>{@code sync}: initializes only {@code LibArgusChatModel} (single KV cache context).</li>
     * </ul>
     */
    @NotBlank(message = "chatMode must not be blank")
    @Pattern(
        regexp = "(?i)^(both|stream|sync)$",
        message = "chatMode must be one of: both, stream, sync"
    )
    private String chatMode = "both";

    /**
     * KV cache context window length in tokens ({@code ARGUS_CONTEXT_LENGTH}).
     */
    @Min(value = 128, message = "contextLength must be at least 128 tokens")
    @Max(value = 131072, message = "contextLength must not exceed 131072 tokens")
    private int contextLength = 4096;

    /**
     * Default and ceiling generated output tokens per response ({@code ARGUS_MAX_OUTPUT_TOKENS}).
     */
    @Min(value = 1, message = "maxOutputTokens must be at least 1")
    @Max(value = 32768, message = "maxOutputTokens must not exceed 32768")
    private int maxOutputTokens = 512;

    /**
     * Number of model layers to offload to GPU ({@code ARGUS_GPU_LAYERS}, 0 = CPU-only).
     */
    @Min(value = 0, message = "gpuLayers must be >= 0")
    @Max(value = 999, message = "gpuLayers must be <= 999")
    private int gpuLayers = 0;

    /**
     * Number of CPU threads for inference ({@code ARGUS_CPU_THREADS}, 0 = automatic physical core heuristic).
     */
    @Min(value = 0, message = "cpuThreads must be >= 0")
    @Max(value = 512, message = "cpuThreads must be <= 512")
    private int cpuThreads = 0;

    /**
     * Default sampling temperature ({@code ARGUS_TEMPERATURE}, <= 0.0 is greedy argmax).
     */
    @DecimalMin(value = "0.0", message = "temperature must be >= 0.0")
    @DecimalMax(value = "2.0", message = "temperature must be <= 2.0")
    private float temperature = 0.7f;

    /**
     * Maximum concurrent inference requests admitted by the workbench ({@code ARGUS_MAX_CONCURRENT_INFERENCE}).
     */
    @Min(value = 1, message = "maxConcurrentInference must be at least 1")
    @Max(value = 64, message = "maxConcurrentInference must not exceed 64")
    private int maxConcurrentInference = 1;

    /**
     * Maximum characters accepted in any single message or total conversation payload ({@code ARGUS_MAX_PROMPT_CHARS}).
     */
    @Min(value = 64, message = "maxPromptChars must be at least 64")
    @Max(value = 500000, message = "maxPromptChars must not exceed 500000")
    private int maxPromptChars = 12000;

    /**
     * Maximum number of conversation messages accepted per request ({@code ARGUS_MAX_HISTORY_MESSAGES}).
     */
    @Min(value = 1, message = "maxHistoryMessages must be at least 1")
    @Max(value = 200, message = "maxHistoryMessages must not exceed 200")
    private int maxHistoryMessages = 20;

    /**
     * Maximum raw HTTP request body size in bytes accepted on {@code /api/**} endpoints
     * before JSON deserialization ({@code ARGUS_MAX_REQUEST_BODY_BYTES}).
     */
    @Min(value = 512, message = "maxRequestBodyBytes must be at least 512 bytes")
    @Max(value = 10485760, message = "maxRequestBodyBytes must not exceed 10485760 bytes (10 MiB)")
    private long maxRequestBodyBytes = 65536L;

    /**
     * Timeout in seconds for a single synchronous or streaming generation job ({@code ARGUS_INFERENCE_TIMEOUT_SECONDS}).
     */
    @Min(value = 5, message = "inferenceTimeoutSeconds must be at least 5")
    @Max(value = 3600, message = "inferenceTimeoutSeconds must not exceed 3600")
    private int inferenceTimeoutSeconds = 180;

    /**
     * Whether startup should fail immediately if {@code ARGUS_CHAT_MODEL_PATH} is missing or unreadable.
     * When {@code false}, the application starts in a degraded state and automatically recovers once the model
     * file is placed at the configured path.
     */
    private boolean failFastStartup = true;

    /**
     * Trusted server-side system instruction prepended to every conversation ({@code ARGUS_SYSTEM_PROMPT}).
     */
    @NotBlank(message = "systemPrompt must not be blank")
    @Size(max = 4000, message = "systemPrompt must not exceed 4000 characters")
    private String systemPrompt =
        "You are a helpful, accurate, and concise AI assistant running locally in-process via libargus.";

    public String getModelPath() {
        return modelPath;
    }

    public void setModelPath(String modelPath) {
        this.modelPath = modelPath != null ? modelPath.trim() : "";
    }

    public String getChatFormatter() {
        return chatFormatter;
    }

    public void setChatFormatter(String chatFormatter) {
        this.chatFormatter = chatFormatter != null ? chatFormatter.trim() : "auto";
    }

    public String getChatMode() {
        return chatMode;
    }

    public void setChatMode(String chatMode) {
        this.chatMode = chatMode != null ? chatMode.trim() : "both";
    }

    public int getContextLength() {
        return contextLength;
    }

    public void setContextLength(int contextLength) {
        this.contextLength = contextLength;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getGpuLayers() {
        return gpuLayers;
    }

    public void setGpuLayers(int gpuLayers) {
        this.gpuLayers = gpuLayers;
    }

    public int getCpuThreads() {
        return cpuThreads;
    }

    public void setCpuThreads(int cpuThreads) {
        this.cpuThreads = cpuThreads;
    }

    public float getTemperature() {
        return temperature;
    }

    public void setTemperature(float temperature) {
        this.temperature = temperature;
    }

    public int getMaxConcurrentInference() {
        return maxConcurrentInference;
    }

    public void setMaxConcurrentInference(int maxConcurrentInference) {
        this.maxConcurrentInference = maxConcurrentInference;
    }

    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }

    public void setMaxHistoryMessages(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    public int getInferenceTimeoutSeconds() {
        return inferenceTimeoutSeconds;
    }

    public void setInferenceTimeoutSeconds(int inferenceTimeoutSeconds) {
        this.inferenceTimeoutSeconds = inferenceTimeoutSeconds;
    }

    public boolean isFailFastStartup() {
        return failFastStartup;
    }

    public void setFailFastStartup(boolean failFastStartup) {
        this.failFastStartup = failFastStartup;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
