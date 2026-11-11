package cc.projectargus.workbench.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes {@code src/main/resources/static/app.js} inside a deterministic Node VM harness to verify
 * that clicking <b>New Chat</b> during an in-flight stream immediately aborts the client stream and
 * prevents late {@code token}, {@code complete}, or {@code AbortError} callbacks from repopulating
 * the cleared transcript or {@code conversationHistory}.
 */
class FrontendEpochRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void newChatDuringStreamingPreventsLateCallbacksFromRepopulatingClearedChatAndPreservesNormalStopBehavior()
        throws Exception {
        Process probe = new ProcessBuilder("node", "--version").start();
        Assumptions.assumeTrue(
            probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0,
            "Skipping Node-backed frontend JS regression test because 'node' is not on PATH"
        );

        Path appJsPath = Path.of("src/main/resources/static/app.js").toAbsolutePath();
        assertThat(Files.isRegularFile(appJsPath)).isTrue();

        Path harnessScript = tempDir.resolve("verify-app-js.mjs");
        String script = """
            import fs from "node:fs";
            import vm from "node:vm";
            import assert from "node:assert/strict";

            const appJsPath = process.argv[2];
            const source = fs.readFileSync(appJsPath, "utf8");

            class MockElement {
              constructor(tagName = "div", id = "") {
                this.tagName = tagName;
                this.id = id;
                this.textContent = "";
                this.value = "";
                this.disabled = false;
                this.checked = true;
                this.className = "";
                this.children = [];
                this.attributes = new Map();
                this.listeners = new Map();
                this._classes = new Set();
                this.scrollTop = 0;
                this.scrollHeight = 100;
                this.classList = {
                  add: (...names) => names.forEach((n) => this._classes.add(n)),
                  remove: (...names) => names.forEach((n) => this._classes.delete(n)),
                  toggle: (name, force) => {
                    const shouldHave = force !== undefined ? Boolean(force) : !this._classes.has(name);
                    if (shouldHave) this._classes.add(name);
                    else this._classes.delete(name);
                  },
                  contains: (name) => this._classes.has(name)
                };
              }
              get firstChild() {
                return this.children.length > 0 ? this.children[0] : null;
              }
              appendChild(child) {
                this.children.push(child);
                return child;
              }
              removeChild(child) {
                const idx = this.children.indexOf(child);
                if (idx !== -1) {
                  this.children.splice(idx, 1);
                }
                return child;
              }
              replaceChildren(...nodes) {
                this.children = [...nodes];
              }
              setAttribute(k, v) {
                this.attributes.set(k, String(v));
              }
              getAttribute(k) {
                return this.attributes.get(k) ?? null;
              }
              addEventListener(type, fn) {
                this.listeners.set(type, fn);
              }
              focus() {}
            }

            const elements = new Map();
            function getEl(id) {
              if (!elements.has(id)) {
                const el = new MockElement("div", id);
                if (id === "mode-select") el.value = "stream";
                if (id === "temperature-input") el.value = "0.7";
                if (id === "max-tokens-input") el.value = "256";
                if (id === "alert-banner" || id === "stop-btn") {
                  el.classList.add("hidden");
                }
                elements.set(id, el);
              }
              return elements.get(id);
            }

            let pendingStreamReads = [];
            let stopEndpointCalls = 0;
            let activeAbortSignal = null;

            let copiedTexts = [];
            let pendingTimeouts = [];

            const windowObj = {
              setInterval: () => 1,
              setTimeout: (fn) => {
                pendingTimeouts.push(fn);
                return pendingTimeouts.length;
              },
              crypto: { randomUUID: () => "test-uuid-1234" }
            };

            const navigatorObj = {
              clipboard: {
                writeText: async (text) => {
                  copiedTexts.push(text);
                }
              }
            };

            const documentObj = {
              getElementById: (id) => getEl(id),
              createElement: (tag) => new MockElement(tag),
              querySelectorAll: () => []
            };

            async function mockFetch(url, options = {}) {
              if (url === "/api/status") {
                return {
                  ok: true,
                  status: 200,
                  json: async () => ({
                    status: "ready",
                    chatEnabled: true,
                    binding: "jni",
                    runtimeVersion: "2.3.1",
                    availableBackends: ["CPU"],
                    modelName: "tiny-embed.gguf",
                    chatFormatter: "auto",
                    contextLength: 2048,
                    maxOutputTokens: 256,
                    gpuLayers: 0,
                    maxConcurrentInference: 1,
                    activeInferenceCount: 0,
                    maxPromptChars: 12000,
                    maxHistoryMessages: 20
                  })
                };
              }
              if (url === "/api/chat/stop") {
                stopEndpointCalls += 1;
                return {
                  ok: true,
                  status: 200,
                  json: async () => ({ stopped: true, requestId: "test-uuid-1234" })
                };
              }
              if (url === "/api/chat/stream") {
                activeAbortSignal = options.signal;
                return {
                  ok: true,
                  status: 200,
                  body: {
                    getReader() {
                      return {
                        read() {
                          return new Promise((resolve, reject) => {
                            pendingStreamReads.push({ resolve, reject, signal: options.signal });
                          });
                        },
                        cancel() {}
                      };
                    }
                  }
                };
              }
              throw new Error("Unexpected URL: " + url);
            }

            const context = vm.createContext({
              window: windowObj,
              document: documentObj,
              navigator: navigatorObj,
              fetch: mockFetch,
              AbortController: globalThis.AbortController,
              TextDecoder: globalThis.TextDecoder,
              TextEncoder: globalThis.TextEncoder,
              performance: globalThis.performance,
              console
            });

            vm.runInContext(source, context);
            const hooks = windowObj.__argusWorkbenchTestHooks;
            assert.ok(hooks, "Expected __argusWorkbenchTestHooks to be exposed");

            const encoder = new TextEncoder();
            const flushMicrotasks = () => new Promise((r) => setTimeout(r, 15));
            await flushMicrotasks();

            // --- Scenario 1: Click New Chat during active streaming ---
            getEl("prompt-input").value = "First user question before New Chat";
            const submitPromise1 = hooks.submitPrompt({ preventDefault() {} });
            await flushMicrotasks();

            assert.equal(getEl("messages-list").children.length, 2, "User + assistant cards should be in DOM during stream");
            assert.equal(pendingStreamReads.length, 1);

            // Emit start + initial token chunk
            const read1 = pendingStreamReads.shift();
            read1.resolve({
              done: false,
              value: encoder.encode(
                'event:start\\ndata:{"requestId":"req-1","promptTokens":12,"evictedMessages":2}\\n\\n' +
                'event:token\\ndata:{"text":"Partial before clear "}\\n\\n'
              )
            });
            await flushMicrotasks();
            assert.equal(pendingStreamReads.length, 1);

            // Now user clicks New Chat while stream is still open!
            const epochBeforeClear = hooks.getChatSessionEpoch();
            hooks.clearTranscript();
            assert.equal(hooks.getChatSessionEpoch(), epochBeforeClear + 1);
            // AbortController must be aborted synchronously before /api/chat/stop even completes
            assert.equal(activeAbortSignal.aborted, true, "AbortController should abort synchronously on New Chat");
            assert.equal(hooks.getConversationHistory().length, 0);
            assert.equal(getEl("messages-list").children.length, 0);

            // Now deliver a late SSE token + complete frame AND finish the stream after New Chat was clicked
            const lateRead = pendingStreamReads.shift();
            lateRead.resolve({
              done: false,
              value: encoder.encode(
                'event:token\\ndata:{"text":"GHOST_TOKEN_AFTER_CLEAR"}\\n\\n' +
                'event:complete\\ndata:{"requestId":"req-1","text":"Partial before clear GHOST_TOKEN_AFTER_CLEAR","finishReason":"STOP","cancelled":false,"usage":{"inputTokens":12,"outputTokens":6,"totalTokens":18},"evictedMessages":0,"durationMs":50}\\n\\n'
              )
            });
            await flushMicrotasks();
            if (pendingStreamReads.length > 0) {
              pendingStreamReads.shift().resolve({ done: true, value: undefined });
            }
            await submitPromise1;

            // Verify the cleared chat was NOT repopulated by late streaming callbacks!
            assert.equal(
              hooks.getConversationHistory().length,
              0,
              "Cleared conversationHistory must remain empty after late streaming callbacks"
            );
            assert.equal(
              getEl("messages-list").children.length,
              0,
              "Cleared DOM messages-list must remain empty after late streaming callbacks"
            );
            assert.equal(
              getEl("empty-state").classList.contains("hidden"),
              false,
              "Empty state must remain visible after New Chat"
            );
            assert.equal(getEl("meta-turns").textContent, "0 / 20");

            // --- Scenario 2: Ordinary Stop button during active stream still preserves partial response ---
            getEl("prompt-input").value = "Second question to test Stop button";
            const submitPromise2 = hooks.submitPrompt({ preventDefault() {} });
            await flushMicrotasks();

            const streamRead2 = pendingStreamReads.shift();
            streamRead2.resolve({
              done: false,
              value: encoder.encode('event:token\\ndata:{"text":"Kept partial reply"}\\n\\n')
            });
            await flushMicrotasks();

            const stopPromise = hooks.stopActiveGeneration();
            const abortRead2 = pendingStreamReads.shift();
            const abortErr = new Error("Aborted");
            abortErr.name = "AbortError";
            abortRead2.reject(abortErr);
            await stopPromise;
            await submitPromise2;

            const historyAfterStop = hooks.getConversationHistory();
            assert.equal(historyAfterStop.length, 2, "User + partial assistant message should be kept on normal Stop");
            assert.equal(historyAfterStop[1].role, "assistant");
            assert.equal(historyAfterStop[1].content, "Kept partial reply");
            assert.equal(getEl("meta-turns").textContent, "2 / 20");

            // --- Scenario 3: Normal streaming completion with evictedMessages formats 'MESSAGES EVICTED: 2' ---
            getEl("prompt-input").value = "Third question with evicted messages";
            const submitPromise3 = hooks.submitPrompt({ preventDefault() {} });
            await flushMicrotasks();

            const streamRead3 = pendingStreamReads.shift();
            streamRead3.resolve({
              done: false,
              value: encoder.encode(
                'event:complete\\ndata:{"requestId":"req-3","text":"Complete reply","finishReason":"STOP","cancelled":false,"usage":{"inputTokens":20,"outputTokens":4,"totalTokens":24},"evictedMessages":2,"durationMs":40}\\n\\n'
              )
            });
            await flushMicrotasks();
            pendingStreamReads.shift().resolve({ done: true, value: undefined });
            await submitPromise3;

            const lastAssistantCard = getEl("messages-list").children.at(-1);
            const footerEl = lastAssistantCard.children.at(-1);
            const pillTexts = footerEl.children.map((c) => c.textContent);
            assert.ok(
              pillTexts.includes("MESSAGES EVICTED: 2"),
              "Expected footer pills to contain 'MESSAGES EVICTED: 2', got: " + JSON.stringify(pillTexts)
            );

            // --- Scenario 4: Assistant COPY button reveals on complete, copies reply text, and resets ---
            const headerActionsEl = lastAssistantCard.children[0].children[1];
            const copyBtnEl = headerActionsEl.children[1];
            assert.equal(copyBtnEl.classList.contains("hidden"), false, "COPY button must be visible after completion");
            assert.equal(copyBtnEl.textContent, "COPY");

            const clickHandler = copyBtnEl.listeners.get("click");
            assert.ok(clickHandler, "Expected click listener on COPY button");
            clickHandler();
            await flushMicrotasks();

            assert.deepEqual(copiedTexts, ["Complete reply"]);
            assert.equal(copyBtnEl.textContent, "COPIED \\u2713");
            assert.equal(copyBtnEl.classList.contains("copied"), true);
            assert.equal(pendingTimeouts.length, 1);
            pendingTimeouts.shift()();
            assert.equal(copyBtnEl.textContent, "COPY");
            assert.equal(copyBtnEl.classList.contains("copied"), false);
            """;

        Files.writeString(harnessScript, script, StandardCharsets.UTF_8);

        Process nodeProc = new ProcessBuilder("node", harnessScript.toString(), appJsPath.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(nodeProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = nodeProc.waitFor(10, TimeUnit.SECONDS);

        assertThat(finished).as("Node frontend verification timed out. Output:\n%s", output).isTrue();
        assertThat(nodeProc.exitValue())
            .as("Node frontend verification failed with output:\n%s", output)
            .isZero();
    }
}
