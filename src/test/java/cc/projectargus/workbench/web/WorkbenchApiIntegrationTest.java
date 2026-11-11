package cc.projectargus.workbench.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.projectargus.workbench.runtime.CancellationToken;
import cc.projectargus.workbench.runtime.InferenceConcurrencyGate;
import cc.projectargus.workbench.runtime.InferenceGateway;
import cc.projectargus.workbench.runtime.RuntimeStatusSnapshot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "argus.chat.native-runtime-enabled=false",
    "argus.chat.max-concurrent-inference=1",
    "argus.chat.context-length=2048",
    "argus.chat.max-output-tokens=256",
    "argus.chat.max-request-body-bytes=2048"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class WorkbenchApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ControllableTestGateway testGateway;

    @Autowired
    private InferenceConcurrencyGate concurrencyGate;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private TokenCountEstimator tokenCountEstimator;

    @BeforeEach
    void resetGateway() {
        concurrencyGate.cancelAllActive();
        testGateway.reset();
    }

    @Test
    void servesStaticWorkbenchIndexAndBrandAssetsWithDefensiveSecurityHeaders() throws Exception {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("LOCAL AI WORKBENCH")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<span class=\"telemetry-label\">MESSAGES</span>")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SESSION TURNS"))))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/assets/argus-logo.png")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/assets/argus-banner.png")));

        mockMvc.perform(get("/assets/argus-logo.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));

        mockMvc.perform(get("/assets/argus-banner.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void statusEndpointReturnsReadyTelemetryWithoutPathLeakage() throws Exception {
        mockMvc.perform(get("/api/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ready"))
            .andExpect(jsonPath("$.chatEnabled").value(true))
            .andExpect(jsonPath("$.binding").value("jni"))
            .andExpect(jsonPath("$.modelName").value("qwen2.5-instruct.gguf"));
    }

    @Test
    void synchronousChatEndpointValidatesAndReturnsStructuredResponse() throws Exception {
        String payload = """
            {
              "requestId": "sync-test-1",
              "messages": [
                { "role": "user", "content": "Explain local inference." }
              ],
              "temperature": 0.1,
              "maxOutputTokens": 64
            }
            """;

        mockMvc.perform(
                post("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("sync-test-1"))
            .andExpect(jsonPath("$.reply").value("In-process libargus response"))
            .andExpect(jsonPath("$.finishReason").value("STOP"))
            .andExpect(jsonPath("$.usage.inputTokens").value(16))
            .andExpect(jsonPath("$.usage.outputTokens").value(5));

        assertThat(concurrencyGate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void streamingChatEmitsJsonFramedEventsIncludingMultilineTokensAndReleasesPermit() throws Exception {
        // Include newlines and SSE delimiter lookalikes inside a token piece to verify JSON framing safety
        testGateway.streamPieces = List.of("Line 1\n\ndata: injected\n", "Line 2");

        String payload = """
            {
              "requestId": "stream-test-1",
              "prompt": "Test multiline token streaming"
            }
            """;

        MvcResult mvcResult = mockMvc.perform(
                post("/api/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content(payload)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        String sseBody = mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(sseBody)
            .contains("event:start")
            .contains("event:token")
            .contains("\\n\\ndata: injected\\n")
            .contains("event:complete")
            .contains("\"finishReason\":\"STOP\"");

        assertThat(concurrencyGate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void stopEndpointCancelsActiveStreamRejectsOverloadAndRestoresPermit() throws Exception {
        CountDownLatch enteredStream = new CountDownLatch(1);
        testGateway.blockUntilAbortedLatch = enteredStream;

        String streamPayload = """
            {
              "requestId": "cancellable-stream-1",
              "prompt": "Hold the inference lock until cancelled"
            }
            """;

        MvcResult streamMvcResult = mockMvc.perform(
                post("/api/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content(streamPayload)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        assertThat(enteredStream.await(5, TimeUnit.SECONDS)).isTrue();

        // While stream 1 is active, a second request reusing the same requestId must fail with 409 Conflict
        mockMvc.perform(
                post("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"cancellable-stream-1\",\"prompt\":\"Duplicate ID attempt\"}")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("duplicate_request_id"));

        // While stream 1 holds the single permit, a second request with a distinct requestId must fail with 429 Too Many Requests
        mockMvc.perform(
                post("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"distinct-overload-2\",\"prompt\":\"Overload attempt\"}")
            )
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "2"))
            .andExpect(jsonPath("$.code").value("inference_busy"));

        // Now trigger cooperative cancellation via POST /api/chat/stop
        mockMvc.perform(
                post("/api/chat/stop")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"cancellable-stream-1\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stopped").value(true))
            .andExpect(jsonPath("$.requestId").value("cancellable-stream-1"));

        String cancelledSse = mockMvc.perform(asyncDispatch(streamMvcResult))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(cancelledSse)
            .contains("event:complete")
            .contains("\"cancelled\":true")
            .contains("\"finishReason\":\"CANCELLED\"");

        // Permit must be cleanly returned after cancellation
        assertThat(concurrencyGate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void streamingRuntimeExceptionEmitsSanitizedErrorEventAndReleasesPermit() throws Exception {
        testGateway.failStreamWithException.set(true);

        MvcResult mvcResult = mockMvc.perform(
                post("/api/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("{\"prompt\":\"Trigger internal failure\"}")
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        String sseBody = mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(sseBody)
            .contains("event:error")
            .contains("\"code\":\"inference_failed\"")
            .doesNotContain("/secret/internal/path.gguf");

        assertThat(concurrencyGate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedJsonRequestBodiesWith413PayloadTooLargeBeforeEnteringController() throws Exception {
        String oversizedPrompt = "A".repeat(2500);
        String oversizedBody = "{\"requestId\":\"oversized-1\",\"prompt\":\"" + oversizedPrompt + "\"}";

        // 1. Declared Content-Length exceeding max-request-body-bytes (2048 in test properties)
        mockMvc.perform(
                post("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(oversizedBody)
            )
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("payload_too_large"));

        // 2. Streaming / chunked request without a declared Content-Length header
        mockMvc.perform(
                post("/api/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content(oversizedBody)
                    .with(request -> {
                        request.removeHeader("Content-Length");
                        return request;
                    })
            )
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("payload_too_large"));

        assertThat(concurrencyGate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    void standardLangChain4jBeansExecuteAndCloseCancellationTokensWithoutLeaks() {
        ChatResponse syncResp = chatModel.doChat(
            ChatRequest.builder().messages(UserMessage.from("Bean sync test")).build()
        );
        assertThat(syncResp.aiMessage().text()).isEqualTo("In-process libargus response");
        assertThat(testGateway.allocatedTokens).hasSize(1);
        assertThat(testGateway.allocatedTokens.get(0).isClosed()).isTrue();

        List<String> chunks = new ArrayList<>();
        AtomicReference<ChatResponse> completedRef = new AtomicReference<>();
        streamingChatModel.doChat(
            ChatRequest.builder().messages(UserMessage.from("Bean stream test")).build(),
            new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    chunks.add(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    completedRef.set(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    throw new AssertionError("Unexpected stream error", error);
                }
            }
        );
        assertThat(String.join("", chunks)).isEqualTo("Hello world");
        assertThat(completedRef.get()).isNotNull();
        assertThat(testGateway.allocatedTokens).hasSize(2);
        assertThat(testGateway.allocatedTokens.get(1).isClosed()).isTrue();

        testGateway.failStreamWithException.set(true);
        AtomicReference<Throwable> streamErr = new AtomicReference<>();
        streamingChatModel.doChat(
            ChatRequest.builder().messages(UserMessage.from("Bean stream error test")).build(),
            new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {}

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {}

                @Override
                public void onError(Throwable error) {
                    streamErr.set(error);
                }
            }
        );
        assertThat(streamErr.get()).isNotNull();
        assertThat(testGateway.allocatedTokens).hasSize(3);
        assertThat(testGateway.allocatedTokens.get(2).isClosed()).isTrue();

        assertThat(tokenCountEstimator.estimateTokenCountInText("")).isZero();
        assertThat(
            tokenCountEstimator.estimateTokenCountInMessages(
                List.of(SystemMessage.from("System"), UserMessage.from("User"))
            )
        ).isEqualTo(16);
    }

    @TestConfiguration
    static class TestGatewayConfig {
        @Bean
        ControllableTestGateway controllableTestGateway() {
            return new ControllableTestGateway();
        }
    }

    static final class ControllableTestGateway implements InferenceGateway {
        volatile List<String> streamPieces = List.of("Hello ", "world");
        volatile CountDownLatch blockUntilAbortedLatch = null;
        final AtomicBoolean failStreamWithException = new AtomicBoolean(false);
        final List<CancellationToken> allocatedTokens = new CopyOnWriteArrayList<>();

        void reset() {
            streamPieces = List.of("Hello ", "world");
            blockUntilAbortedLatch = null;
            failStreamWithException.set(false);
            allocatedTokens.clear();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public RuntimeStatusSnapshot getStatusSnapshot(int activeInferenceCount) {
            return new RuntimeStatusSnapshot(
                "ready", true, "jni", "2.3.1", List.of("CPU"), "qwen2.5-instruct.gguf",
                "auto", 2048, 256, 0, 1, activeInferenceCount, null, null
            );
        }

        @Override
        public CancellationToken createCancellationToken() {
            CancellationToken token = CancellationToken.inMemory();
            allocatedTokens.add(token);
            return token;
        }

        @Override
        public int countTokens(List<ChatMessage> messages) {
            return messages.size() * 8;
        }

        @Override
        public ChatResponse chat(ChatRequest request, CancellationToken cancellationToken) {
            return ChatResponse.builder()
                .aiMessage(AiMessage.from("In-process libargus response"))
                .tokenUsage(new TokenUsage(16, 5))
                .finishReason(FinishReason.STOP)
                .build();
        }

        @Override
        public void streamChat(
            ChatRequest request,
            StreamingChatResponseHandler handler,
            CancellationToken cancellationToken
        ) {
            if (failStreamWithException.get()) {
                handler.onError(new RuntimeException("Native failure at /secret/internal/path.gguf"));
                return;
            }
            CountDownLatch latch = blockUntilAbortedLatch;
            if (latch != null) {
                handler.onPartialResponse("Partial token before stop...");
                latch.countDown();
                long deadline = System.currentTimeMillis() + 5000;
                while (!cancellationToken.isAborted() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                handler.onCompleteResponse(
                    ChatResponse.builder()
                        .aiMessage(AiMessage.from("Partial token before stop..."))
                        .tokenUsage(new TokenUsage(16, 4))
                        .finishReason(FinishReason.OTHER)
                        .build()
                );
                return;
            }

            StringBuilder full = new StringBuilder();
            for (String piece : streamPieces) {
                if (cancellationToken.isAborted()) {
                    break;
                }
                full.append(piece);
                handler.onPartialResponse(piece);
            }
            handler.onCompleteResponse(
                ChatResponse.builder()
                    .aiMessage(AiMessage.from(full.toString()))
                    .tokenUsage(new TokenUsage(16, streamPieces.size()))
                    .finishReason(FinishReason.STOP)
                    .build()
            );
        }
    }
}
