package cc.projectargus.workbench.runtime;

import cc.projectargus.libargus.ArgusAbortFlag;
import cc.projectargus.libargus.ArgusBackend;
import cc.projectargus.libargus.ArgusModel;
import cc.projectargus.libargus.internal.ArgusCommonValidation;
import cc.projectargus.libargus.langchain4j.LibArgusChatFormatter;
import cc.projectargus.libargus.langchain4j.LibArgusChatModel;
import cc.projectargus.libargus.langchain4j.LibArgusStreamingChatModel;
import cc.projectargus.libargus.langchain4j.LibArgusTokenCountEstimator;
import cc.projectargus.workbench.chat.InvalidChatRequestException;
import cc.projectargus.workbench.config.ArgusStartupFailureException;
import cc.projectargus.workbench.config.ArgusWorkbenchProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single explicit lifecycle owner for {@code libargus} native backend initialization, shared
 * {@link ArgusModel} weights, {@link LibArgusTokenCountEstimator}, {@link LibArgusChatModel},
 * and {@link LibArgusStreamingChatModel}.
 *
 * <p>Guarantees deterministic forward initialization, automatic stack unwinding on partial startup
 * failure, and reverse-order native resource teardown on application shutdown.
 */
@Component
@ConditionalOnProperty(
    prefix = "argus.chat",
    name = "native-runtime-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ArgusRuntimeManager implements InferenceGateway, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ArgusRuntimeManager.class);
    private static final String PANAMA_PROBE_CLASS = "cc.projectargus.libargus.internal.ArgusBindings";
    private static final byte[] GGUF_MAGIC = new byte[] {'G', 'G', 'U', 'F'};
    private static final long MIN_GGUF_BYTES = 64L;

    private final ArgusWorkbenchProperties properties;
    private final InferenceConcurrencyGate concurrencyGate;
    private final String activeBinding;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    private ArgusModel sharedModel;
    private LibArgusTokenCountEstimator tokenEstimator;
    private LibArgusChatModel chatModel;
    private LibArgusStreamingChatModel streamingChatModel;
    private String loadedModelFileName;
    private String runtimeVersion = "2.3.1";
    private List<String> availableBackends = List.of();
    private boolean ready = false;
    private boolean destroyed = false;
    private String lastErrorCode = "model_not_configured";
    private String lastErrorMessage =
        "Set ARGUS_CHAT_MODEL_PATH to a readable local chat/instruct GGUF model file.";

    public ArgusRuntimeManager(
        ArgusWorkbenchProperties properties,
        InferenceConcurrencyGate concurrencyGate
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.concurrencyGate = Objects.requireNonNull(concurrencyGate, "concurrencyGate");
        this.activeBinding = detectActiveBinding();
    }

    /**
     * Detects whether Project Panama FFM ({@code libargus-core}) or JNI ({@code libargus-jni})
     * is active on the current classpath without triggering native library initialization.
     */
    public static String detectActiveBinding() {
        return detectActiveBinding(ArgusRuntimeManager.class.getClassLoader());
    }

    static String detectActiveBinding(ClassLoader classLoader) {
        try {
            Class.forName(PANAMA_PROBE_CLASS, false, classLoader);
            return "panama-ffm";
        } catch (ClassNotFoundException | LinkageError ignored) {
            return "jni";
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.getMaxOutputTokens() >= properties.getContextLength()) {
            throw new ArgusStartupFailureException(
                "invalid_token_budget",
                "ARGUS_MAX_OUTPUT_TOKENS ("
                    + properties.getMaxOutputTokens()
                    + ") must be strictly less than ARGUS_CONTEXT_LENGTH ("
                    + properties.getContextLength()
                    + ").",
                "Decrease ARGUS_MAX_OUTPUT_TOKENS or increase ARGUS_CONTEXT_LENGTH so room remains for prompt tokens."
            );
        }
        resolveChatMode(properties.getChatMode());

        try {
            initializeNativeRuntime();
        } catch (ArgusStartupFailureException ex) {
            if (properties.isFailFastStartup()) {
                throw ex;
            }
            log.warn(
                "Starting Local AI Workbench in degraded state [{}]: {}",
                ex.getErrorCode(),
                ex.getMessage()
            );
        }
    }

    /**
     * Validates and normalizes the configured {@code ARGUS_CHAT_MODE} ({@code both}, {@code stream}, or {@code sync}).
     */
    public static String resolveChatMode(String configured) {
        if (configured == null || configured.isBlank()) {
            return "both";
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "both", "stream", "sync" -> normalized;
            default -> throw new ArgusStartupFailureException(
                "unsupported_chat_mode",
                "Unsupported ARGUS_CHAT_MODE value '" + configured + "'.",
                "Use one of: both, stream, sync."
            );
        };
    }

    /**
     * Resolves a {@link LibArgusChatFormatter} from the configured formatter name.
     */
    public static LibArgusChatFormatter resolveFormatter(String configured) {
        if (configured == null || configured.isBlank()) {
            return LibArgusChatFormatter.autoDetect();
        }
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "auto", "autodetect" -> LibArgusChatFormatter.autoDetect();
            case "chatml" -> LibArgusChatFormatter.chatMl();
            case "llama3" -> LibArgusChatFormatter.llama3();
            case "gemma" -> LibArgusChatFormatter.gemma();
            case "mistral" -> LibArgusChatFormatter.mistral();
            case "fallback", "fallbackinstruct" -> LibArgusChatFormatter.fallbackInstruct();
            default -> throw new ArgusStartupFailureException(
                "unsupported_chat_formatter",
                "Unsupported ARGUS_CHAT_FORMATTER value '" + configured + "'.",
                "Use one of: auto, chatml, llama3, gemma, mistral, fallback."
            );
        };
    }

    private void initializeNativeRuntime() {
        lifecycleLock.writeLock().lock();
        try {
            if (destroyed) {
                throw new IllegalStateException("ArgusRuntimeManager has already been destroyed");
            }
            if (ready) {
                return;
            }

            Path modelPath = validateAndResolveModelPath(properties.getModelPath());
            LibArgusChatFormatter formatter = resolveFormatter(properties.getChatFormatter());
            String chatMode = resolveChatMode(properties.getChatMode());
            int cpuThreads = properties.getCpuThreads() > 0
                ? properties.getCpuThreads()
                : ArgusCommonValidation.defaultCpuThreads();

            ArgusModel loadedModel = null;
            LibArgusTokenCountEstimator createdEstimator = null;
            LibArgusChatModel createdChat = null;
            LibArgusStreamingChatModel createdStreaming = null;
            boolean backendInitializedHere = false;

            try {
                if (!ArgusBackend.init()) {
                    throw new ArgusStartupFailureException(
                        "native_backend_init_failed",
                        "libargus native compute backend failed to initialize.",
                        "Verify that the matching libargus-native-* runtime artifact for your OS/CPU/GPU is on the classpath."
                    );
                }
                backendInitializedHere = true;
                this.runtimeVersion = safeRuntimeVersion();
                this.availableBackends = safeAvailableBackends();

                loadedModel = ArgusModel.load(modelPath, properties.getGpuLayers(), false);
                createdEstimator = LibArgusTokenCountEstimator.builder()
                    .model(loadedModel)
                    .chatFormatter(formatter)
                    .build();

                if ("both".equals(chatMode) || "sync".equals(chatMode)) {
                    createdChat = LibArgusChatModel.builder()
                        .model(loadedModel)
                        .chatFormatter(formatter)
                        .contextLength(properties.getContextLength())
                        .cpuThreads(cpuThreads)
                        .gpuLayers(properties.getGpuLayers())
                        .temperature(properties.getTemperature())
                        .maxOutputTokens(properties.getMaxOutputTokens())
                        .build();
                }

                if ("both".equals(chatMode) || "stream".equals(chatMode)) {
                    createdStreaming = LibArgusStreamingChatModel.builder()
                        .model(loadedModel)
                        .chatFormatter(formatter)
                        .contextLength(properties.getContextLength())
                        .cpuThreads(cpuThreads)
                        .gpuLayers(properties.getGpuLayers())
                        .temperature(properties.getTemperature())
                        .maxOutputTokens(properties.getMaxOutputTokens())
                        .build();
                }

                this.sharedModel = loadedModel;
                this.tokenEstimator = createdEstimator;
                this.chatModel = createdChat;
                this.streamingChatModel = createdStreaming;
                this.loadedModelFileName = modelPath.getFileName() != null
                    ? modelPath.getFileName().toString()
                    : "model.gguf";
                this.ready = true;
                this.lastErrorCode = null;
                this.lastErrorMessage = null;

                log.info(
                    "Initialized libargus v{} via {} (backends={}, model={}, chatMode={}, contextLength={}, maxOutputTokens={}, gpuLayers={}, threads={})",
                    runtimeVersion,
                    activeBinding,
                    availableBackends,
                    loadedModelFileName,
                    chatMode,
                    properties.getContextLength(),
                    properties.getMaxOutputTokens(),
                    properties.getGpuLayers(),
                    cpuThreads
                );
            } catch (ArgusStartupFailureException ex) {
                unwindPartialInit(createdStreaming, createdChat, createdEstimator, loadedModel, backendInitializedHere);
                this.ready = false;
                this.lastErrorCode = ex.getErrorCode();
                this.lastErrorMessage = ex.getMessage();
                throw ex;
            } catch (UnsatisfiedLinkError | NoClassDefFoundError linkErr) {
                unwindPartialInit(createdStreaming, createdChat, createdEstimator, loadedModel, backendInitializedHere);
                this.ready = false;
                this.lastErrorCode = "native_runtime_unavailable";
                this.lastErrorMessage =
                    "Native libargus library could not be loaded for this operating system or architecture.";
                log.error("Native library load failure during startup", linkErr);
                throw new ArgusStartupFailureException(
                    this.lastErrorCode,
                    this.lastErrorMessage,
                    "Ensure the matching cc.projectargus:libargus-native-<os>-<backend>:2.3.1 dependency profile is active (e.g. -Plinux-cpu, -Pmacos-metal, -Pwindows-cpu).",
                    linkErr
                );
            } catch (Throwable t) {
                unwindPartialInit(createdStreaming, createdChat, createdEstimator, loadedModel, backendInitializedHere);
                this.ready = false;
                this.lastErrorCode = "model_load_failed";
                this.lastErrorMessage =
                    "Failed to load GGUF model or allocate native inference context. Check available RAM/VRAM and model compatibility.";
                log.error("Model/context initialization failure", t);
                throw new ArgusStartupFailureException(
                    this.lastErrorCode,
                    this.lastErrorMessage,
                    "Verify that ARGUS_CHAT_MODEL_PATH points to a valid, uncorrupted GGUF model supported by libargus and that sufficient memory is available.",
                    t
                );
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private Path validateAndResolveModelPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            this.lastErrorCode = "model_not_configured";
            this.lastErrorMessage =
                "Set ARGUS_CHAT_MODEL_PATH to a supported local chat/instruct GGUF model.";
            throw new ArgusStartupFailureException(
                this.lastErrorCode,
                this.lastErrorMessage,
                "Export ARGUS_CHAT_MODEL_PATH=/absolute/path/to/model.gguf before starting the application."
            );
        }

        final Path path;
        try {
            path = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            this.lastErrorCode = "invalid_model_path";
            this.lastErrorMessage = "Configured ARGUS_CHAT_MODEL_PATH is not a valid filesystem path.";
            throw new ArgusStartupFailureException(
                this.lastErrorCode,
                this.lastErrorMessage,
                "Provide a valid local filesystem path in ARGUS_CHAT_MODEL_PATH.",
                ex
            );
        }

        if (!Files.exists(path)) {
            this.lastErrorCode = "model_file_not_found";
            this.lastErrorMessage =
                "Configured GGUF model file does not exist at the path specified by ARGUS_CHAT_MODEL_PATH.";
            throw new ArgusStartupFailureException(
                this.lastErrorCode,
                this.lastErrorMessage,
                "Verify the file path in ARGUS_CHAT_MODEL_PATH and confirm the GGUF model has been downloaded."
            );
        }

        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            this.lastErrorCode = "model_file_unreadable";
            this.lastErrorMessage =
                "Configured ARGUS_CHAT_MODEL_PATH is not a readable regular file.";
            throw new ArgusStartupFailureException(
                this.lastErrorCode,
                this.lastErrorMessage,
                "Point ARGUS_CHAT_MODEL_PATH to a readable .gguf file (not a directory) and check file permissions."
            );
        }

        try {
            long size = Files.size(path);
            if (size < MIN_GGUF_BYTES) {
                this.lastErrorCode = "invalid_gguf_file";
                this.lastErrorMessage =
                    "Configured model file is too small to be a valid GGUF model.";
                throw new ArgusStartupFailureException(
                    this.lastErrorCode,
                    this.lastErrorMessage,
                    "Verify that the GGUF download completed cleanly and is not an empty or truncated file."
                );
            }
            byte[] header = new byte[GGUF_MAGIC.length];
            try (InputStream in = Files.newInputStream(path)) {
                int read = in.readNBytes(header, 0, GGUF_MAGIC.length);
                if (read < GGUF_MAGIC.length
                    || header[0] != GGUF_MAGIC[0]
                    || header[1] != GGUF_MAGIC[1]
                    || header[2] != GGUF_MAGIC[2]
                    || header[3] != GGUF_MAGIC[3]) {
                    this.lastErrorCode = "invalid_gguf_header";
                    this.lastErrorMessage =
                        "Configured model file does not have a valid GGUF magic header ('GGUF').";
                    throw new ArgusStartupFailureException(
                        this.lastErrorCode,
                        this.lastErrorMessage,
                        "Supply a standard GGUF v2/v3 model file in ARGUS_CHAT_MODEL_PATH."
                    );
                }
            }
        } catch (IOException ioEx) {
            this.lastErrorCode = "model_file_io_error";
            this.lastErrorMessage = "Failed to read the configured GGUF model file header.";
            throw new ArgusStartupFailureException(
                this.lastErrorCode,
                this.lastErrorMessage,
                "Check filesystem permissions and disk health for ARGUS_CHAT_MODEL_PATH.",
                ioEx
            );
        }

        return path;
    }

    private void tryRecoverIfModelNowPresent() {
        lifecycleLock.readLock().lock();
        try {
            if (ready || destroyed) {
                return;
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }

        String rawPath = properties.getModelPath();
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        try {
            Path candidate = Path.of(rawPath.trim());
            if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
                return;
            }
            initializeNativeRuntime();
        } catch (Exception ignored) {
            // Diagnostics are already updated by initializeNativeRuntime()
        }
    }

    @Override
    public boolean isReady() {
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            return ready && !destroyed;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public RuntimeStatusSnapshot getStatusSnapshot(int activeInferenceCount) {
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            return new RuntimeStatusSnapshot(
                ready ? "ready" : "not_ready",
                ready,
                activeBinding,
                runtimeVersion,
                availableBackends,
                loadedModelFileName,
                properties.getChatFormatter(),
                properties.getContextLength(),
                properties.getMaxOutputTokens(),
                properties.getGpuLayers(),
                properties.getMaxConcurrentInference(),
                activeInferenceCount,
                lastErrorCode,
                lastErrorMessage
            );
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public CancellationToken createCancellationToken() {
        ensureReadyUnderReadLock();
        return CancellationToken.forNative(new ArgusAbortFlag());
    }

    @Override
    public int countTokens(List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            ensureReadyState();
            return tokenEstimator.estimateTokenCountInMessages(messages);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public int countTextTokens(String text) {
        Objects.requireNonNull(text, "text");
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            ensureReadyState();
            return tokenEstimator.estimateTokenCountInText(text);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public int countMessageTokens(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            ensureReadyState();
            return tokenEstimator.estimateTokenCountInMessage(message);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public ChatResponse chat(ChatRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            ensureReadyState();
            if (chatModel == null) {
                throw new InvalidChatRequestException(
                    "sync_chat_disabled",
                    "Synchronous chat is disabled when ARGUS_CHAT_MODE="
                        + resolveChatMode(properties.getChatMode())
                        + ". Use POST /api/chat/stream or set ARGUS_CHAT_MODE=both."
                );
            }
            ArgusAbortFlag flag = cancellationToken != null ? cancellationToken.nativeFlag() : null;
            return chatModel.doChat(request, flag);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public void streamChat(
        ChatRequest request,
        StreamingChatResponseHandler handler,
        CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            ensureReadyState();
            if (streamingChatModel == null) {
                throw new InvalidChatRequestException(
                    "stream_chat_disabled",
                    "Streaming chat is disabled when ARGUS_CHAT_MODE="
                        + resolveChatMode(properties.getChatMode())
                        + ". Use POST /api/chat or set ARGUS_CHAT_MODE=both."
                );
            }
            ArgusAbortFlag flag = cancellationToken != null ? cancellationToken.nativeFlag() : null;
            streamingChatModel.doChat(request, handler, flag);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private void ensureReadyUnderReadLock() {
        tryRecoverIfModelNowPresent();
        lifecycleLock.readLock().lock();
        try {
            ensureReadyState();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private void ensureReadyState() {
        if (destroyed) {
            throw new ModelNotReadyException(
                "runtime_shutting_down",
                "Local AI runtime is shutting down."
            );
        }
        if (!ready) {
            throw new ModelNotReadyException(
                lastErrorCode != null ? lastErrorCode : "model_not_ready",
                lastErrorMessage != null
                    ? lastErrorMessage
                    : "Set ARGUS_CHAT_MODEL_PATH to a supported local GGUF model."
            );
        }
    }

    private static String safeRuntimeVersion() {
        try {
            String v = ArgusBackend.getVersion();
            return (v != null && !v.isBlank()) ? v : "2.3.1";
        } catch (Throwable ignored) {
            return "2.3.1";
        }
    }

    private static List<String> safeAvailableBackends() {
        try {
            List<String> backends = ArgusBackend.getAvailableBackends();
            return backends != null ? List.copyOf(backends) : List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static void unwindPartialInit(
        LibArgusStreamingChatModel streaming,
        LibArgusChatModel syncChat,
        LibArgusTokenCountEstimator estimator,
        ArgusModel model,
        boolean freeBackend
    ) {
        closeQuietly(streaming, "LibArgusStreamingChatModel");
        closeQuietly(syncChat, "LibArgusChatModel");
        closeQuietly(estimator, "LibArgusTokenCountEstimator");
        closeQuietly(model, "ArgusModel");
        if (freeBackend) {
            try {
                ArgusBackend.free();
            } catch (Throwable t) {
                log.debug("Ignored error during partial-init ArgusBackend.free()", t);
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable, String label) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable t) {
            log.warn("Error closing {} during teardown", label, t);
        }
    }

    @Override
    public void destroy() {
        // 1. Signal any in-flight native generations to abort immediately so read locks release fast
        int cancelled = concurrencyGate.cancelAllActive();
        if (cancelled > 0) {
            log.info("Signaled cancellation to {} in-flight inference request(s) prior to shutdown", cancelled);
        }

        // 2. Acquire exclusive lifecycle write lock and close resources in deterministic reverse order
        lifecycleLock.writeLock().lock();
        try {
            if (destroyed) {
                return;
            }
            destroyed = true;
            boolean wasReady = ready;
            ready = false;

            closeQuietly(streamingChatModel, "LibArgusStreamingChatModel");
            streamingChatModel = null;

            closeQuietly(chatModel, "LibArgusChatModel");
            chatModel = null;

            closeQuietly(tokenEstimator, "LibArgusTokenCountEstimator");
            tokenEstimator = null;

            closeQuietly(sharedModel, "ArgusModel");
            sharedModel = null;

            if (wasReady) {
                try {
                    ArgusBackend.free();
                } catch (Throwable t) {
                    log.warn("Error freeing ArgusBackend during shutdown", t);
                }
            }
            log.info("Closed libargus runtime resources cleanly in reverse initialization order");
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
}
