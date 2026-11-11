package cc.projectargus.workbench.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.projectargus.workbench.chat.ChatService;
import cc.projectargus.workbench.chat.InvalidChatRequestException;
import cc.projectargus.workbench.config.ArgusLangChain4jConfig;
import cc.projectargus.workbench.config.ArgusWorkbenchProperties;
import cc.projectargus.workbench.web.dto.ChatRequestDto;
import cc.projectargus.workbench.web.dto.ChatResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in end-to-end native integration test that loads a real GGUF model via {@code libargus-jni}
 * and exercises token counting, synchronous chat, multi-token streaming, live model recovery,
 * and deterministic teardown.
 *
 * <p>Skipped automatically when {@code ARGUS_CHAT_MODEL_PATH} (or {@code -Dargus.chat.model-path})
 * is not provided.
 */
class NativeModelIntegrationTest {

    @TempDir
    Path tempDir;

    private static Path resolveConfiguredModelPath() {
        String configuredPath = System.getProperty("argus.chat.model-path");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("ARGUS_CHAT_MODEL_PATH");
        }
        Assumptions.assumeTrue(
            configuredPath != null && !configuredPath.isBlank(),
            "Skipping native GGUF integration test: set ARGUS_CHAT_MODEL_PATH or -Dargus.chat.model-path=/path/to/model.gguf to run."
        );
        Path modelPath = Path.of(configuredPath.trim());
        Assumptions.assumeTrue(
            Files.isRegularFile(modelPath),
            "Skipping native GGUF integration test: configured model file does not exist: " + modelPath
        );
        return modelPath;
    }

    @Test
    void loadsRealGgufModelExecutesSyncAndStreamingChatAndClosesCleanly() throws Exception {
        Path modelPath = resolveConfiguredModelPath();

        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setModelPath(modelPath.toString());
        props.setContextLength(512);
        props.setMaxOutputTokens(8);
        props.setGpuLayers(0);
        props.setTemperature(0.0f);
        props.setFailFastStartup(true);

        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);
        ArgusRuntimeManager runtimeManager = new ArgusRuntimeManager(props, gate);
        ChatService chatService = new ChatService(runtimeManager, gate, props, new ObjectMapper());

        try {
            runtimeManager.afterPropertiesSet();
            assertThat(runtimeManager.isReady()).isTrue();

            RuntimeStatusSnapshot status = runtimeManager.getStatusSnapshot(0);
            assertThat(status.status()).isEqualTo("ready");
            assertThat(status.chatEnabled()).isTrue();
            assertThat(status.binding()).isEqualTo(ArgusRuntimeManager.detectActiveBinding());
            assertThat(status.modelName()).isEqualTo(modelPath.getFileName().toString());

            // 1. Verify native BPE token estimation (including raw text vs formatted chat message via @Bean adapter)
            ArgusLangChain4jConfig langChainConfig = new ArgusLangChain4jConfig();
            TokenCountEstimator estimatorBean = langChainConfig.argusTokenCountEstimator(runtimeManager);
            ChatModel chatBean = langChainConfig.argusChatModel(runtimeManager);
            int rawHelloTokens = estimatorBean.estimateTokenCountInText("Hello");
            int formattedHelloTokens = estimatorBean.estimateTokenCountInMessage(UserMessage.from("Hello"));
            assertThat(rawHelloTokens).isPositive();
            assertThat(formattedHelloTokens).isGreaterThan(rawHelloTokens);
            assertThat(
                chatBean.doChat(
                    ChatRequest.builder()
                        .messages(UserMessage.from("Hi"))
                        .parameters(ChatRequestParameters.builder().temperature(0.0).maxOutputTokens(2).build())
                        .build()
                ).aiMessage().text()
            ).isNotEmpty();

            int tokens = runtimeManager.countTokens(List.of(
                SystemMessage.from(props.getSystemPrompt()),
                UserMessage.from("Hello")
            ));
            assertThat(tokens).isPositive();

            // 2. Verify synchronous chat through ChatService
            ChatResponseDto syncResponse = chatService.chat(
                new ChatRequestDto(null, "Hello", 0.0f, 4, "native-sync-1")
            );
            assertThat(syncResponse.requestId()).isEqualTo("native-sync-1");
            assertThat(syncResponse.usage().inputTokens()).isPositive();
            assertThat(syncResponse.usage().outputTokens()).isEqualTo(4);
            assertThat(syncResponse.finishReason()).isNotBlank();

            // 3. Verify incremental streaming chat with CancellationToken
            List<String> fragments = new ArrayList<>();
            CompletableFuture<ChatResponse> streamDone = new CompletableFuture<>();
            ChatRequest streamReq = ChatRequest.builder()
                .messages(UserMessage.from("Hello"))
                .parameters(
                    ChatRequestParameters.builder()
                        .temperature(0.0)
                        .maxOutputTokens(4)
                        .build()
                )
                .build();

            try (CancellationToken cancelToken = runtimeManager.createCancellationToken()) {
                runtimeManager.streamChat(
                    streamReq,
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            fragments.add(partialResponse);
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            streamDone.complete(completeResponse);
                        }

                        @Override
                        public void onError(Throwable error) {
                            streamDone.completeExceptionally(error);
                        }
                    },
                    cancelToken
                );
            }

            ChatResponse completed = streamDone.get(30, TimeUnit.SECONDS);
            assertThat(completed).isNotNull();
            assertThat(completed.tokenUsage().inputTokenCount()).isPositive();
            assertThat(fragments).hasSizeGreaterThanOrEqualTo(2);
            assertThat(String.join("", fragments)).isEqualTo(completed.aiMessage().text());
        } finally {
            chatService.destroy();
            runtimeManager.destroy();
            assertThat(runtimeManager.isReady()).isFalse();
        }
    }

    @Test
    void recoversLiveWhenModelFileArrivesAfterDegradedStartup() throws Exception {
        Path sourceModel = resolveConfiguredModelPath();
        Path delayedDestination = tempDir.resolve("delayed-arrival.gguf");

        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setModelPath(delayedDestination.toString());
        props.setContextLength(256);
        props.setMaxOutputTokens(4);
        props.setGpuLayers(0);
        props.setTemperature(0.0f);
        props.setFailFastStartup(false);

        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);
        ArgusRuntimeManager runtimeManager = new ArgusRuntimeManager(props, gate);

        try {
            runtimeManager.afterPropertiesSet();
            assertThat(runtimeManager.isReady()).isFalse();
            assertThat(runtimeManager.getStatusSnapshot(0).status()).isEqualTo("not_ready");

            // Simulate user finishing model download to the configured path
            Files.copy(sourceModel, delayedDestination, StandardCopyOption.REPLACE_EXISTING);

            assertThat(runtimeManager.isReady()).isTrue();
            RuntimeStatusSnapshot recovered = runtimeManager.getStatusSnapshot(0);
            assertThat(recovered.status()).isEqualTo("ready");
            assertThat(recovered.chatEnabled()).isTrue();
            assertThat(recovered.modelName()).isEqualTo("delayed-arrival.gguf");
        } finally {
            runtimeManager.destroy();
        }
    }

    @Test
    void streamOnlyChatModeAllocatesSingleStreamingAdapterAndRejectsSyncRequests() throws Exception {
        Path modelPath = resolveConfiguredModelPath();

        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setModelPath(modelPath.toString());
        props.setChatMode("stream");
        props.setContextLength(256);
        props.setMaxOutputTokens(4);
        props.setGpuLayers(0);
        props.setTemperature(0.0f);
        props.setFailFastStartup(true);

        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);
        ArgusRuntimeManager runtimeManager = new ArgusRuntimeManager(props, gate);
        ChatService chatService = new ChatService(runtimeManager, gate, props, new ObjectMapper());

        try {
            runtimeManager.afterPropertiesSet();
            assertThat(runtimeManager.isReady()).isTrue();

            assertThatThrownBy(() -> chatService.chat(new ChatRequestDto(null, "Hello", 0.0f, 4, "sync-disabled-1")))
                .isInstanceOf(InvalidChatRequestException.class)
                .extracting(ex -> ((InvalidChatRequestException) ex).getErrorCode())
                .isEqualTo("sync_chat_disabled");

            CompletableFuture<ChatResponse> streamDone = new CompletableFuture<>();
            ChatRequest streamReq = ChatRequest.builder()
                .messages(UserMessage.from("Hello"))
                .parameters(
                    ChatRequestParameters.builder()
                        .temperature(0.0)
                        .maxOutputTokens(4)
                        .build()
                )
                .build();

            try (CancellationToken cancelToken = runtimeManager.createCancellationToken()) {
                runtimeManager.streamChat(
                    streamReq,
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            streamDone.complete(completeResponse);
                        }

                        @Override
                        public void onError(Throwable error) {
                            streamDone.completeExceptionally(error);
                        }
                    },
                    cancelToken
                );
            }
            assertThat(streamDone.get(30, TimeUnit.SECONDS)).isNotNull();
        } finally {
            chatService.destroy();
            runtimeManager.destroy();
        }
    }
}
