package cc.projectargus.workbench.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.projectargus.workbench.config.ArgusStartupFailureAnalyzer;
import cc.projectargus.workbench.config.ArgusStartupFailureException;
import cc.projectargus.workbench.config.ArgusWorkbenchProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalysis;

class ArgusRuntimeLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void failFastStartupRejectsMissingModelPathWithActionableException() {
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setModelPath("");
        props.setFailFastStartup(true);

        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);
        ArgusRuntimeManager manager = new ArgusRuntimeManager(props, gate);

        assertThatThrownBy(manager::afterPropertiesSet)
            .isInstanceOf(ArgusStartupFailureException.class)
            .satisfies(ex -> {
                ArgusStartupFailureException startupEx = (ArgusStartupFailureException) ex;
                assertThat(startupEx.getErrorCode()).isEqualTo("model_not_configured");
                assertThat(startupEx.getMessage()).contains("ARGUS_CHAT_MODEL_PATH");
                assertThat(startupEx.getRemediation()).contains("ARGUS_CHAT_MODEL_PATH");

                ArgusStartupFailureAnalyzer analyzer = new ArgusStartupFailureAnalyzer();
                FailureAnalysis analysis = analyzer.analyze(startupEx);
                assertThat(analysis).isNotNull();
                assertThat(analysis.getDescription()).contains("model_not_configured");
                assertThat(analysis.getAction()).contains("ARGUS_CHAT_MODEL_PATH");
            });
    }

    @Test
    void failFastStartupRejectsNonExistentModelFileWithoutLeakingAbsolutePath() {
        Path missingFile = tempDir.resolve("nonexistent-model.gguf");
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setModelPath(missingFile.toString());
        props.setFailFastStartup(true);

        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);
        ArgusRuntimeManager manager = new ArgusRuntimeManager(props, gate);

        assertThatThrownBy(manager::afterPropertiesSet)
            .isInstanceOf(ArgusStartupFailureException.class)
            .satisfies(ex -> {
                ArgusStartupFailureException startupEx = (ArgusStartupFailureException) ex;
                assertThat(startupEx.getErrorCode()).isEqualTo("model_file_not_found");
                assertThat(startupEx.getMessage()).doesNotContain(tempDir.toString());
            });
    }

    @Test
    void failFastStartupRejectsTooSmallOrNonGgufFile() throws IOException {
        Path tinyInvalid = tempDir.resolve("corrupt.gguf");
        Files.writeString(tinyInvalid, "not a gguf", StandardCharsets.UTF_8);

        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setModelPath(tinyInvalid.toString());
        props.setFailFastStartup(true);

        ArgusRuntimeManager manager = new ArgusRuntimeManager(props, new InferenceConcurrencyGate(props));
        assertThatThrownBy(manager::afterPropertiesSet)
            .isInstanceOf(ArgusStartupFailureException.class)
            .extracting(ex -> ((ArgusStartupFailureException) ex).getErrorCode())
            .isEqualTo("invalid_gguf_file");

        Path badHeaderFile = tempDir.resolve("html-error-page.gguf");
        Files.writeString(
            badHeaderFile,
            "<!DOCTYPE html><html><body>404 Not Found padding bytes to exceed 64 bytes minimum</body></html>",
            StandardCharsets.UTF_8
        );
        props.setModelPath(badHeaderFile.toString());

        ArgusRuntimeManager manager2 = new ArgusRuntimeManager(props, new InferenceConcurrencyGate(props));
        assertThatThrownBy(manager2::afterPropertiesSet)
            .isInstanceOf(ArgusStartupFailureException.class)
            .extracting(ex -> ((ArgusStartupFailureException) ex).getErrorCode())
            .isEqualTo("invalid_gguf_header");
    }

    @Test
    void failFastStartupRejectsMaxOutputTokensGreaterOrEqualToContextLength() {
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setContextLength(1024);
        props.setMaxOutputTokens(1024);

        ArgusRuntimeManager manager = new ArgusRuntimeManager(props, new InferenceConcurrencyGate(props));
        assertThatThrownBy(manager::afterPropertiesSet)
            .isInstanceOf(ArgusStartupFailureException.class)
            .extracting(ex -> ((ArgusStartupFailureException) ex).getErrorCode())
            .isEqualTo("invalid_token_budget");
    }

    @Test
    void degradedStartupRecordsSanitizedNotReadyStateAndBlocksInferenceCleanly() {
        Path missing = tempDir.resolve("pending-download.gguf");
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setModelPath(missing.toString());
        props.setFailFastStartup(false);

        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);
        ArgusRuntimeManager manager = new ArgusRuntimeManager(props, gate);

        manager.afterPropertiesSet();
        assertThat(manager.isReady()).isFalse();

        RuntimeStatusSnapshot status = manager.getStatusSnapshot(0);
        assertThat(status.status()).isEqualTo("not_ready");
        assertThat(status.chatEnabled()).isFalse();
        assertThat(status.errorCode()).isEqualTo("model_file_not_found");
        assertThat(status.errorMessage()).doesNotContain(tempDir.toString());

        assertThatThrownBy(manager::createCancellationToken)
            .isInstanceOf(ModelNotReadyException.class)
            .extracting(ex -> ((ModelNotReadyException) ex).getErrorCode())
            .isEqualTo("model_file_not_found");

        manager.destroy();
    }

    @Test
    void resolveFormatterSupportsAllDocumentedFamiliesAndRejectsUnknown() {
        assertThat(ArgusRuntimeManager.resolveFormatter("auto")).isNotNull();
        assertThat(ArgusRuntimeManager.resolveFormatter("autoDetect")).isNotNull();
        assertThat(ArgusRuntimeManager.resolveFormatter("chatml")).isNotNull();
        assertThat(ArgusRuntimeManager.resolveFormatter("llama3")).isNotNull();
        assertThat(ArgusRuntimeManager.resolveFormatter("gemma")).isNotNull();
        assertThat(ArgusRuntimeManager.resolveFormatter("mistral")).isNotNull();
        assertThat(ArgusRuntimeManager.resolveFormatter("fallback")).isNotNull();

        assertThatThrownBy(() -> ArgusRuntimeManager.resolveFormatter("unknown-template"))
            .isInstanceOf(ArgusStartupFailureException.class)
            .extracting(ex -> ((ArgusStartupFailureException) ex).getErrorCode())
            .isEqualTo("unsupported_chat_formatter");
    }

    @Test
    void resolveChatModeSupportsBothStreamAndSyncAndRejectsUnknown() {
        assertThat(ArgusRuntimeManager.resolveChatMode(null)).isEqualTo("both");
        assertThat(ArgusRuntimeManager.resolveChatMode("  ")).isEqualTo("both");
        assertThat(ArgusRuntimeManager.resolveChatMode("BOTH")).isEqualTo("both");
        assertThat(ArgusRuntimeManager.resolveChatMode("stream")).isEqualTo("stream");
        assertThat(ArgusRuntimeManager.resolveChatMode("SYNC")).isEqualTo("sync");

        assertThatThrownBy(() -> ArgusRuntimeManager.resolveChatMode("invalid-mode"))
            .isInstanceOf(ArgusStartupFailureException.class)
            .extracting(ex -> ((ArgusStartupFailureException) ex).getErrorCode())
            .isEqualTo("unsupported_chat_mode");
    }

    @Test
    void cancellationTokenIsIdempotentAndSafeAcrossConcurrentAbortAndClose() throws InterruptedException {
        CancellationToken token = CancellationToken.inMemory();
        assertThat(token.isAborted()).isFalse();
        assertThat(token.isClosed()).isFalse();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final boolean closeThread = (i % 2 == 0);
            pool.execute(() -> {
                try {
                    startLatch.await();
                    if (closeThread) {
                        token.close();
                    } else {
                        token.abort();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(token.isAborted()).isTrue();
        assertThat(token.isClosed()).isTrue();

        // Calling abort() or close() after close must be a safe no-op
        token.abort();
        token.close();
    }

    @Test
    void detectActiveBindingMatchesClasspathArtifactAndFallsBackToJniWhenPanamaAbsent() {
        boolean panamaOnClasspath;
        try {
            Class.forName("cc.projectargus.libargus.internal.ArgusBindings", false, getClass().getClassLoader());
            panamaOnClasspath = true;
        } catch (ClassNotFoundException e) {
            panamaOnClasspath = false;
        }

        assertThat(ArgusRuntimeManager.detectActiveBinding())
            .isEqualTo(panamaOnClasspath ? "panama-ffm" : "jni");

        ClassLoader jniOnlyClassLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("cc.projectargus.libargus.internal.ArgusBindings".equals(name)) {
                    throw new ClassNotFoundException("Simulated JDK 17/21 classpath without libargus-core");
                }
                return super.loadClass(name, resolve);
            }
        };
        assertThat(ArgusRuntimeManager.detectActiveBinding(jniOnlyClassLoader)).isEqualTo("jni");
    }
}
