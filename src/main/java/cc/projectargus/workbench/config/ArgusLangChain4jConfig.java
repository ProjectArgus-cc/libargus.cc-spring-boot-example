package cc.projectargus.workbench.config;

import cc.projectargus.workbench.runtime.CancellationToken;
import cc.projectargus.workbench.runtime.InferenceGateway;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes standard constructor-injectable LangChain4j {@link ChatModel}, {@link StreamingChatModel},
 * and {@link TokenCountEstimator} beans backed by the shared {@link InferenceGateway}.
 *
 * <p>Downstream Spring services (or LangChain4j {@code AiServices}) can inject these standard
 * interfaces directly without allocating duplicate {@code ArgusModel} weights or KV cache contexts,
 * while {@code ArgusRuntimeManager} remains the single deterministic owner of native teardown.
 */
@Configuration(proxyBeanMethods = false)
public class ArgusLangChain4jConfig {

    @Bean(destroyMethod = "")
    public ChatModel argusChatModel(InferenceGateway inferenceGateway) {
        Objects.requireNonNull(inferenceGateway, "inferenceGateway");
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                Objects.requireNonNull(chatRequest, "chatRequest");
                try (CancellationToken token = inferenceGateway.createCancellationToken()) {
                    return inferenceGateway.chat(chatRequest, token);
                }
            }
        };
    }

    @Bean(destroyMethod = "")
    public StreamingChatModel argusStreamingChatModel(InferenceGateway inferenceGateway) {
        Objects.requireNonNull(inferenceGateway, "inferenceGateway");
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                Objects.requireNonNull(chatRequest, "chatRequest");
                Objects.requireNonNull(handler, "handler");

                CancellationToken token = inferenceGateway.createCancellationToken();
                StreamingChatResponseHandler guardedHandler = new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        handler.onPartialResponse(partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        try {
                            token.close();
                        } finally {
                            handler.onCompleteResponse(completeResponse);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        try {
                            token.close();
                        } finally {
                            handler.onError(error);
                        }
                    }
                };

                try {
                    inferenceGateway.streamChat(chatRequest, guardedHandler, token);
                } catch (Throwable error) {
                    token.close();
                    throw error;
                }
            }
        };
    }

    @Bean(destroyMethod = "")
    public TokenCountEstimator argusTokenCountEstimator(InferenceGateway inferenceGateway) {
        Objects.requireNonNull(inferenceGateway, "inferenceGateway");
        return new TokenCountEstimator() {
            @Override
            public int estimateTokenCountInText(String text) {
                return inferenceGateway.countTextTokens(text);
            }

            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                return inferenceGateway.countMessageTokens(message);
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                Objects.requireNonNull(messages, "messages");
                List<ChatMessage> list = new ArrayList<>();
                for (ChatMessage msg : messages) {
                    if (msg != null) {
                        list.add(msg);
                    }
                }
                return list.isEmpty() ? 0 : inferenceGateway.countTokens(list);
            }
        };
    }
}
