package cc.projectargus.workbench.chat;

import cc.projectargus.workbench.config.ArgusWorkbenchProperties;
import cc.projectargus.workbench.runtime.CancellationToken;
import cc.projectargus.workbench.runtime.InferenceConcurrencyGate;
import cc.projectargus.workbench.runtime.InferenceConcurrencyGate.PermitLease;
import cc.projectargus.workbench.runtime.InferenceGateway;
import cc.projectargus.workbench.web.dto.ApiErrorDto;
import cc.projectargus.workbench.web.dto.ChatMessageDto;
import cc.projectargus.workbench.web.dto.ChatRequestDto;
import cc.projectargus.workbench.web.dto.ChatResponseDto;
import cc.projectargus.workbench.web.dto.StopResponseDto;
import cc.projectargus.workbench.web.dto.StreamCompleteEventDto;
import cc.projectargus.workbench.web.dto.StreamStartEventDto;
import cc.projectargus.workbench.web.dto.StreamTokenEventDto;
import cc.projectargus.workbench.web.dto.TokenUsageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Orchestrates request validation, server-owned {@link SystemMessage} injection, BPE token budgeting
 * with sliding turn eviction, bounded concurrency leasing, synchronous chat, and SSE streaming chat.
 */
@Service
public class ChatService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final InferenceGateway inferenceGateway;
    private final InferenceConcurrencyGate concurrencyGate;
    private final ArgusWorkbenchProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamingExecutor;
    private final ScheduledExecutorService timeoutScheduler;

    public ChatService(
        InferenceGateway inferenceGateway,
        InferenceConcurrencyGate concurrencyGate,
        ArgusWorkbenchProperties properties,
        ObjectMapper objectMapper
    ) {
        this.inferenceGateway = Objects.requireNonNull(inferenceGateway, "inferenceGateway");
        this.concurrencyGate = Objects.requireNonNull(concurrencyGate, "concurrencyGate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");

        int poolSize = Math.max(1, properties.getMaxConcurrentInference());
        AtomicInteger workerIndex = new AtomicInteger(1);
        ThreadFactory workerFactory = runnable -> {
            Thread t = new Thread(runnable, "argus-inference-worker-" + workerIndex.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.streamingExecutor = Executors.newFixedThreadPool(poolSize, workerFactory);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "argus-inference-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Executes a synchronous multi-turn chat generation.
     */
    public ChatResponseDto chat(ChatRequestDto requestDto) {
        ensureChatModeAllowsSync();
        NormalizedRequest normalized = validateAndNormalizeRequest(requestDto);
        CancellationToken cancellationToken = inferenceGateway.createCancellationToken();

        try (PermitLease lease = concurrencyGate.acquireOrReject(normalized.requestId(), cancellationToken)) {
            BudgetedConversation budgeted = buildBudgetedConversation(normalized);
            ChatRequest chatRequest = buildLangChainRequest(budgeted.messages(), normalized);

            long startNanos = System.nanoTime();
            ScheduledFuture<?> timeoutHandle = scheduleTimeoutWatchdog(lease.cancellationToken());
            try {
                ChatResponse response = inferenceGateway.chat(chatRequest, lease.cancellationToken());
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                String replyText = response != null && response.aiMessage() != null && response.aiMessage().text() != null
                    ? response.aiMessage().text()
                    : "";
                TokenUsageDto usageDto = toTokenUsageDto(response != null ? response.tokenUsage() : null, budgeted.promptTokens());
                String finishReason = resolveFinishReason(
                    response != null ? response.finishReason() : null,
                    lease.cancellationToken().isAborted()
                );

                log.info(
                    "Completed sync chat requestId={} promptTokens={} outputTokens={} evicted={} finishReason={} durationMs={}",
                    normalized.requestId(),
                    usageDto.inputTokens(),
                    usageDto.outputTokens(),
                    budgeted.evictedMessages(),
                    finishReason,
                    durationMs
                );

                return new ChatResponseDto(
                    normalized.requestId(),
                    replyText,
                    finishReason,
                    usageDto,
                    budgeted.evictedMessages(),
                    durationMs
                );
            } finally {
                timeoutHandle.cancel(false);
            }
        }
    }

    /**
     * Initiates a Server-Sent Events (SSE) streaming chat generation.
     *
     * <p>Validation, readiness checks, permit acquisition, and token budgeting execute synchronously
     * before the {@link SseEmitter} is returned so HTTP 400/429/503 errors are reported cleanly.
     */
    public SseEmitter streamChat(ChatRequestDto requestDto) {
        ensureChatModeAllowsStream();
        NormalizedRequest normalized = validateAndNormalizeRequest(requestDto);
        CancellationToken cancellationToken = inferenceGateway.createCancellationToken();
        PermitLease lease = concurrencyGate.acquireOrReject(normalized.requestId(), cancellationToken);

        final BudgetedConversation budgeted;
        final ChatRequest chatRequest;
        try {
            budgeted = buildBudgetedConversation(normalized);
            chatRequest = buildLangChainRequest(budgeted.messages(), normalized);
        } catch (Throwable t) {
            lease.close();
            throw t;
        }

        long timeoutMs = TimeUnit.SECONDS.toMillis(properties.getInferenceTimeoutSeconds());
        SseEmitter emitter = new SseEmitter(timeoutMs);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        emitter.onTimeout(() -> {
            clientDisconnected.set(true);
            lease.cancellationToken().abort();
            emitter.complete();
        });
        emitter.onCompletion(() -> {
            lease.cancellationToken().abort();
        });
        emitter.onError(err -> {
            clientDisconnected.set(true);
            lease.cancellationToken().abort();
        });

        try {
            streamingExecutor.execute(() -> runStreamingJob(
                emitter,
                lease,
                normalized,
                budgeted,
                chatRequest,
                clientDisconnected
            ));
        } catch (RejectedExecutionException rejected) {
            lease.close();
            throw new IllegalStateException("Streaming executor rejected inference task", rejected);
        }

        return emitter;
    }

    /**
     * Signals cooperative cancellation to an active request (or all active requests if {@code requestId} is blank).
     */
    public StopResponseDto stopGeneration(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            String trimmed = requestId.trim();
            boolean stopped = concurrencyGate.cancelRequest(trimmed);
            return new StopResponseDto(stopped, trimmed);
        }
        int count = concurrencyGate.cancelAllActive();
        return new StopResponseDto(count > 0, null);
    }

    private void ensureChatModeAllowsSync() {
        String mode = properties.getChatMode() == null
            ? "both"
            : properties.getChatMode().trim().toLowerCase(Locale.ROOT);
        if (!"both".equals(mode) && !"sync".equals(mode)) {
            throw new InvalidChatRequestException(
                "sync_chat_disabled",
                "Synchronous chat is disabled when ARGUS_CHAT_MODE="
                    + mode
                    + ". Use POST /api/chat/stream or set ARGUS_CHAT_MODE=both."
            );
        }
    }

    private void ensureChatModeAllowsStream() {
        String mode = properties.getChatMode() == null
            ? "both"
            : properties.getChatMode().trim().toLowerCase(Locale.ROOT);
        if (!"both".equals(mode) && !"stream".equals(mode)) {
            throw new InvalidChatRequestException(
                "stream_chat_disabled",
                "Streaming chat is disabled when ARGUS_CHAT_MODE="
                    + mode
                    + ". Use POST /api/chat or set ARGUS_CHAT_MODE=both."
            );
        }
    }

    private void runStreamingJob(
        SseEmitter emitter,
        PermitLease lease,
        NormalizedRequest normalized,
        BudgetedConversation budgeted,
        ChatRequest chatRequest,
        AtomicBoolean clientDisconnected
    ) {
        long startNanos = System.nanoTime();
        ScheduledFuture<?> timeoutHandle = scheduleTimeoutWatchdog(lease.cancellationToken());
        StringBuilder accumulatedText = new StringBuilder();
        AtomicBoolean terminalEventSent = new AtomicBoolean(false);

        try (PermitLease ignored = lease) {
            if (!sendSseEvent(
                emitter,
                "start",
                new StreamStartEventDto(
                    normalized.requestId(),
                    budgeted.promptTokens(),
                    budgeted.evictedMessages()
                ),
                clientDisconnected,
                lease.cancellationToken()
            )) {
                return;
            }

            inferenceGateway.streamChat(
                chatRequest,
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (partialResponse == null || partialResponse.isEmpty()) {
                            return;
                        }
                        if (clientDisconnected.get()) {
                            lease.cancellationToken().abort();
                            return;
                        }
                        accumulatedText.append(partialResponse);
                        sendSseEvent(
                            emitter,
                            "token",
                            new StreamTokenEventDto(partialResponse),
                            clientDisconnected,
                            lease.cancellationToken()
                        );
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        if (!terminalEventSent.compareAndSet(false, true)) {
                            return;
                        }
                        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                        boolean cancelled = lease.cancellationToken().isAborted();
                        String finalText = completeResponse != null
                            && completeResponse.aiMessage() != null
                            && completeResponse.aiMessage().text() != null
                            && !completeResponse.aiMessage().text().isEmpty()
                            ? completeResponse.aiMessage().text()
                            : accumulatedText.toString();

                        TokenUsageDto usageDto = toTokenUsageDto(
                            completeResponse != null ? completeResponse.tokenUsage() : null,
                            budgeted.promptTokens()
                        );
                        String finishReason = resolveFinishReason(
                            completeResponse != null ? completeResponse.finishReason() : null,
                            cancelled
                        );

                        log.info(
                            "Completed stream chat requestId={} promptTokens={} outputTokens={} evicted={} finishReason={} cancelled={} durationMs={}",
                            normalized.requestId(),
                            usageDto.inputTokens(),
                            usageDto.outputTokens(),
                            budgeted.evictedMessages(),
                            finishReason,
                            cancelled,
                            durationMs
                        );

                        sendSseEvent(
                            emitter,
                            "complete",
                            new StreamCompleteEventDto(
                                normalized.requestId(),
                                finalText,
                                finishReason,
                                cancelled,
                                usageDto,
                                budgeted.evictedMessages(),
                                durationMs
                            ),
                            clientDisconnected,
                            lease.cancellationToken()
                        );
                        completeEmitterQuietly(emitter);
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleStreamFailure(
                            emitter,
                            lease.cancellationToken(),
                            normalized,
                            budgeted,
                            accumulatedText,
                            startNanos,
                            terminalEventSent,
                            clientDisconnected,
                            error
                        );
                    }
                },
                lease.cancellationToken()
            );
        } catch (Throwable t) {
            handleStreamFailure(
                emitter,
                lease.cancellationToken(),
                normalized,
                budgeted,
                accumulatedText,
                startNanos,
                terminalEventSent,
                clientDisconnected,
                t
            );
        } finally {
            timeoutHandle.cancel(false);
        }
    }

    private void handleStreamFailure(
        SseEmitter emitter,
        CancellationToken cancellationToken,
        NormalizedRequest normalized,
        BudgetedConversation budgeted,
        StringBuilder accumulatedText,
        long startNanos,
        AtomicBoolean terminalEventSent,
        AtomicBoolean clientDisconnected,
        Throwable error
    ) {
        if (!terminalEventSent.compareAndSet(false, true)) {
            return;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (cancellationToken.isAborted()) {
            log.info(
                "Stream chat cancelled requestId={} durationMs={}",
                normalized.requestId(),
                durationMs
            );
            TokenUsageDto usage = new TokenUsageDto(budgeted.promptTokens(), 0, budgeted.promptTokens());
            sendSseEvent(
                emitter,
                "complete",
                new StreamCompleteEventDto(
                    normalized.requestId(),
                    accumulatedText.toString(),
                    "CANCELLED",
                    true,
                    usage,
                    budgeted.evictedMessages(),
                    durationMs
                ),
                clientDisconnected,
                cancellationToken
            );
            completeEmitterQuietly(emitter);
            return;
        }

        log.error("Streaming inference error for requestId={}", normalized.requestId(), error);
        sendSseEvent(
            emitter,
            "error",
            new ApiErrorDto(
                "inference_failed",
                "Local inference encountered an internal runtime error during generation."
            ),
            clientDisconnected,
            cancellationToken
        );
        completeEmitterQuietly(emitter);
    }

    private boolean sendSseEvent(
        SseEmitter emitter,
        String eventName,
        Object payload,
        AtomicBoolean clientDisconnected,
        CancellationToken cancellationToken
    ) {
        if (clientDisconnected.get()) {
            cancellationToken.abort();
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(
                SseEmitter.event()
                    .name(eventName)
                    .data(json, MediaType.APPLICATION_JSON)
            );
            return true;
        } catch (IOException | IllegalStateException sendErr) {
            clientDisconnected.set(true);
            cancellationToken.abort();
            return false;
        }
    }

    private static void completeEmitterQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Throwable ignored) {
        }
    }

    private ScheduledFuture<?> scheduleTimeoutWatchdog(CancellationToken token) {
        return timeoutScheduler.schedule(
            token::abort,
            properties.getInferenceTimeoutSeconds(),
            TimeUnit.SECONDS
        );
    }

    NormalizedRequest validateAndNormalizeRequest(ChatRequestDto dto) {
        if (dto == null) {
            throw new InvalidChatRequestException(
                "invalid_request",
                "Request body must be a non-empty JSON object."
            );
        }

        boolean hasMessages = dto.messages() != null && !dto.messages().isEmpty();
        boolean hasPrompt = dto.prompt() != null && !dto.prompt().isBlank();

        if (hasMessages && hasPrompt) {
            throw new InvalidChatRequestException(
                "ambiguous_request",
                "Provide either 'messages' or 'prompt', not both."
            );
        }
        if (!hasMessages && !hasPrompt) {
            throw new InvalidChatRequestException(
                "empty_messages",
                "Request must include a non-empty 'messages' array or a non-blank 'prompt' string."
            );
        }

        List<ChatMessageDto> rawTurns = hasMessages
            ? dto.messages()
            : List.of(new ChatMessageDto("user", dto.prompt()));

        if (rawTurns.size() > properties.getMaxHistoryMessages()) {
            throw new InvalidChatRequestException(
                "too_many_messages",
                "Conversation contains "
                    + rawTurns.size()
                    + " messages, exceeding the configured limit of "
                    + properties.getMaxHistoryMessages()
                    + "."
            );
        }

        List<ChatMessage> validatedHistory = new ArrayList<>(rawTurns.size());
        int totalChars = 0;

        for (int i = 0; i < rawTurns.size(); i++) {
            ChatMessageDto turn = rawTurns.get(i);
            if (turn == null) {
                throw new InvalidChatRequestException(
                    "invalid_message",
                    "Message at index " + i + " must not be null."
                );
            }
            if (turn.role() == null || turn.role().isBlank()) {
                throw new InvalidChatRequestException(
                    "invalid_message_role",
                    "Message at index " + i + " is missing a role."
                );
            }
            if (turn.content() == null || turn.content().isBlank()) {
                throw new InvalidChatRequestException(
                    "empty_message_content",
                    "Message at index " + i + " must contain non-blank text."
                );
            }

            String content = turn.content();
            if (content.length() > properties.getMaxPromptChars()) {
                throw new InvalidChatRequestException(
                    "prompt_too_long",
                    "Message at index "
                        + i
                        + " ("
                        + content.length()
                        + " chars) exceeds the maximum allowed size of "
                        + properties.getMaxPromptChars()
                        + " characters."
                );
            }
            totalChars += content.length();
            if (totalChars > properties.getMaxPromptChars()) {
                throw new InvalidChatRequestException(
                    "prompt_too_long",
                    "Total conversation character length ("
                        + totalChars
                        + " chars) exceeds the maximum allowed size of "
                        + properties.getMaxPromptChars()
                        + " characters."
                );
            }

            String normalizedRole = turn.role().trim().toLowerCase(Locale.ROOT);
            switch (normalizedRole) {
                case "user" -> validatedHistory.add(UserMessage.from(content));
                case "assistant" -> validatedHistory.add(AiMessage.from(content));
                case "system", "tool" -> throw new InvalidChatRequestException(
                    "forbidden_message_role",
                    "Client messages may only use 'user' or 'assistant' roles; system instructions are owned by the server."
                );
                default -> throw new InvalidChatRequestException(
                    "invalid_message_role",
                    "Unsupported message role '" + turn.role() + "'. Allowed roles: 'user', 'assistant'."
                );
            }
        }

        ChatMessage lastMessage = validatedHistory.get(validatedHistory.size() - 1);
        if (!(lastMessage instanceof UserMessage)) {
            throw new InvalidChatRequestException(
                "last_message_must_be_user",
                "The final message in the conversation must have role 'user'."
            );
        }

        float effectiveTemperature = properties.getTemperature();
        if (dto.temperature() != null) {
            float candidateTemp = dto.temperature();
            if (Float.isNaN(candidateTemp) || Float.isInfinite(candidateTemp) || candidateTemp < 0.0f || candidateTemp > 2.0f) {
                throw new InvalidChatRequestException(
                    "invalid_temperature",
                    "temperature must be between 0.0 and 2.0."
                );
            }
            effectiveTemperature = candidateTemp;
        }

        int effectiveMaxTokens = properties.getMaxOutputTokens();
        if (dto.maxOutputTokens() != null) {
            int candidateMax = dto.maxOutputTokens();
            if (candidateMax < 1 || candidateMax > properties.getMaxOutputTokens()) {
                throw new InvalidChatRequestException(
                    "invalid_max_output_tokens",
                    "maxOutputTokens must be between 1 and " + properties.getMaxOutputTokens() + "."
                );
            }
            effectiveMaxTokens = candidateMax;
        }

        String resolvedRequestId;
        if (dto.requestId() != null && !dto.requestId().isBlank()) {
            String candidateId = dto.requestId().trim();
            if (!REQUEST_ID_PATTERN.matcher(candidateId).matches()) {
                throw new InvalidChatRequestException(
                    "invalid_request_id",
                    "requestId must match ^[A-Za-z0-9_-]{1,64}$."
                );
            }
            resolvedRequestId = candidateId;
        } else {
            resolvedRequestId = UUID.randomUUID().toString();
        }

        return new NormalizedRequest(
            resolvedRequestId,
            List.copyOf(validatedHistory),
            effectiveTemperature,
            effectiveMaxTokens
        );
    }

    BudgetedConversation buildBudgetedConversation(NormalizedRequest normalized) {
        int maxPromptTokenBudget = properties.getContextLength() - normalized.maxOutputTokens();
        if (maxPromptTokenBudget <= 0) {
            throw new InvalidChatRequestException(
                "context_length_exceeded",
                "Requested maxOutputTokens ("
                    + normalized.maxOutputTokens()
                    + ") leaves no prompt token budget within contextLength ("
                    + properties.getContextLength()
                    + ")."
            );
        }

        SystemMessage systemMessage = SystemMessage.from(properties.getSystemPrompt());
        List<ChatMessage> workingHistory = new ArrayList<>(normalized.history());
        int evictedCount = 0;

        while (true) {
            List<ChatMessage> candidate = new ArrayList<>(workingHistory.size() + 1);
            candidate.add(systemMessage);
            candidate.addAll(workingHistory);

            int tokenCount = inferenceGateway.countTokens(candidate);
            if (tokenCount <= maxPromptTokenBudget) {
                return new BudgetedConversation(List.copyOf(candidate), tokenCount, evictedCount);
            }

            if (workingHistory.size() <= 1) {
                throw new InvalidChatRequestException(
                    "context_length_exceeded",
                    "Prompt requires "
                        + tokenCount
                        + " tokens, which exceeds the available prompt budget of "
                        + maxPromptTokenBudget
                        + " tokens (contextLength="
                        + properties.getContextLength()
                        + ", maxOutputTokens="
                        + normalized.maxOutputTokens()
                        + "). Shorten the prompt."
                );
            }

            // Evict oldest turn; if the next remaining turn is an orphaned assistant reply, evict it too
            workingHistory.remove(0);
            evictedCount++;
            while (workingHistory.size() > 1 && workingHistory.get(0) instanceof AiMessage) {
                workingHistory.remove(0);
                evictedCount++;
            }
        }
    }

    private static ChatRequest buildLangChainRequest(
        List<ChatMessage> messages,
        NormalizedRequest normalized
    ) {
        return ChatRequest.builder()
            .messages(messages)
            .parameters(
                ChatRequestParameters.builder()
                    .temperature((double) normalized.temperature())
                    .maxOutputTokens(normalized.maxOutputTokens())
                    .build()
            )
            .build();
    }

    private static TokenUsageDto toTokenUsageDto(TokenUsage usage, int fallbackPromptTokens) {
        if (usage == null) {
            return new TokenUsageDto(fallbackPromptTokens, 0, fallbackPromptTokens);
        }
        int inTokens = usage.inputTokenCount() != null ? usage.inputTokenCount() : fallbackPromptTokens;
        int outTokens = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        int total = usage.totalTokenCount() != null ? usage.totalTokenCount() : (inTokens + outTokens);
        return new TokenUsageDto(inTokens, outTokens, total);
    }

    private static String resolveFinishReason(FinishReason reason, boolean cancelled) {
        if (cancelled) {
            return "CANCELLED";
        }
        if (reason == null) {
            return "STOP";
        }
        return reason.name();
    }

    @Override
    public void destroy() {
        concurrencyGate.cancelAllActive();
        timeoutScheduler.shutdownNow();
        streamingExecutor.shutdown();
        try {
            if (!streamingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                streamingExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            streamingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    record NormalizedRequest(
        String requestId,
        List<ChatMessage> history,
        float temperature,
        int maxOutputTokens
    ) {}

    record BudgetedConversation(
        List<ChatMessage> messages,
        int promptTokens,
        int evictedMessages
    ) {}
}
