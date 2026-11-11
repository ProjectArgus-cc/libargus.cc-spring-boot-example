package cc.projectargus.workbench.runtime;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Objects;

/**
 * Abstraction over the in-process {@code libargus} LangChain4j adapters and token estimator.
 *
 * <p>Allows deterministic unit testing of chat validation, sliding turn eviction, SSE streaming,
 * and cancellation lifecycles without requiring multi-gigabyte model files.
 */
public interface InferenceGateway {

    /**
     * Returns {@code true} if the native runtime and local chat model are loaded and ready for inference.
     */
    boolean isReady();

    /**
     * Captures a sanitized status snapshot of the runtime and model configuration.
     */
    RuntimeStatusSnapshot getStatusSnapshot(int activeInferenceCount);

    /**
     * Allocates a new request-scoped {@link CancellationToken}.
     */
    CancellationToken createCancellationToken();

    /**
     * Counts the exact number of formatted prompt tokens for the given conversation messages.
     */
    int countTokens(List<ChatMessage> messages);

    /**
     * Counts the exact number of raw BPE/SPM tokens in plain text without chat-template framing.
     */
    default int countTextTokens(String text) {
        Objects.requireNonNull(text, "text");
        return text.isEmpty() ? 0 : countTokens(List.of(UserMessage.from(text)));
    }

    /**
     * Counts the exact number of formatted tokens in a single {@link ChatMessage}.
     */
    default int countMessageTokens(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        return countTokens(List.of(message));
    }

    /**
     * Executes a synchronous chat request with cooperative cancellation support.
     */
    ChatResponse chat(ChatRequest request, CancellationToken cancellationToken);

    /**
     * Executes a streaming chat request with cooperative cancellation support.
     */
    void streamChat(
        ChatRequest request,
        StreamingChatResponseHandler handler,
        CancellationToken cancellationToken
    );
}
