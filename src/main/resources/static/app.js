(function () {
    "use strict";

    // Volatile browser-side conversation transcript: [{ role: "user" | "assistant", content: "..." }]
    let conversationHistory = [];
    let chatSessionEpoch = 0;
    let isGenerating = false;
    let activeAbortController = null;
    let activeRequestId = null;
    let maxPromptCharsLimit = 12000;
    let maxHistoryMessagesLimit = 20;

    // DOM References
    const statusBadge = document.getElementById("status-badge");
    const statusText = document.getElementById("status-text");
    const refreshStatusBtn = document.getElementById("refresh-status-btn");
    const newChatBtn = document.getElementById("new-chat-btn");

    const metaBinding = document.getElementById("meta-binding");
    const metaModel = document.getElementById("meta-model");
    const metaFormatter = document.getElementById("meta-formatter");
    const metaContext = document.getElementById("meta-context");
    const metaBackends = document.getElementById("meta-backends");
    const metaTurns = document.getElementById("meta-turns");

    const alertBanner = document.getElementById("alert-banner");
    const alertCode = document.getElementById("alert-code");
    const alertMessage = document.getElementById("alert-message");
    const alertDismissBtn = document.getElementById("alert-dismiss-btn");

    const transcriptContainer = document.getElementById("transcript-container");
    const emptyState = document.getElementById("empty-state");
    const messagesList = document.getElementById("messages-list");

    const chatForm = document.getElementById("chat-form");
    const modeSelect = document.getElementById("mode-select");
    const tabStream = document.getElementById("tab-stream");
    const tabSync = document.getElementById("tab-sync");
    const temperatureInput = document.getElementById("temperature-input");
    const maxTokensInput = document.getElementById("max-tokens-input");
    const charCounter = document.getElementById("char-counter");
    const promptInput = document.getElementById("prompt-input");
    const sendBtn = document.getElementById("send-btn");
    const stopBtn = document.getElementById("stop-btn");

    function selectExecutionMode(mode) {
        if (isGenerating) {
            return;
        }
        modeSelect.value = mode;
        const isStream = mode === "stream";
        if (tabStream && tabSync) {
            tabStream.classList.toggle("selected", isStream);
            tabStream.setAttribute("aria-selected", String(isStream));
            tabSync.classList.toggle("selected", !isStream);
            tabSync.setAttribute("aria-selected", String(!isStream));
        }
    }

    function generateRequestId() {
        if (window.crypto && typeof window.crypto.randomUUID === "function") {
            return window.crypto.randomUUID();
        }
        return "req-" + Date.now() + "-" + Math.random().toString(36).substring(2, 10);
    }

    function showErrorBanner(code, message) {
        alertCode.textContent = code || "error";
        alertMessage.textContent = message || "An unexpected error occurred.";
        alertBanner.classList.remove("hidden");
    }

    function clearErrorBanner() {
        alertBanner.classList.add("hidden");
        alertCode.textContent = "";
        alertMessage.textContent = "";
    }

    function setBadgeState(stateClass, label) {
        statusBadge.className = "status-badge " + stateClass;
        statusText.textContent = label;
    }

    function updateMessageCounter() {
        metaTurns.textContent = String(conversationHistory.length) + " / " + String(maxHistoryMessagesLimit);
        if (conversationHistory.length === 0 && messagesList.children.length === 0) {
            emptyState.classList.remove("hidden");
        } else {
            emptyState.classList.add("hidden");
        }
    }

    function updateCharCounter() {
        const currentLen = promptInput.value.length;
        charCounter.textContent =
            currentLen.toLocaleString() + " / " + maxPromptCharsLimit.toLocaleString() + " CHARS";
    }

    function setGeneratingState(active) {
        isGenerating = active;
        sendBtn.disabled = active;
        promptInput.disabled = active;
        modeSelect.disabled = active;
        if (tabStream) tabStream.disabled = active;
        if (tabSync) tabSync.disabled = active;
        temperatureInput.disabled = active;
        maxTokensInput.disabled = active;

        if (active) {
            stopBtn.classList.remove("hidden");
            setBadgeState("status-busy", "GENERATING…");
        } else {
            stopBtn.classList.add("hidden");
            activeAbortController = null;
            activeRequestId = null;
        }
    }

    function scrollToBottom() {
        transcriptContainer.scrollTop = transcriptContainer.scrollHeight;
    }

    function createMessageCard(role, initialText, statusLabel) {
        emptyState.classList.add("hidden");

        const card = document.createElement("article");
        card.className = "message-card " + (role === "user" ? "message-user" : "message-assistant");

        const header = document.createElement("div");
        header.className = "message-header";

        const roleSpan = document.createElement("span");
        roleSpan.className = "message-role";
        roleSpan.textContent = role === "user" ? "USER" : "ARGUS ASSISTANT";

        const statusSpan = document.createElement("span");
        statusSpan.className = "message-status";
        statusSpan.textContent = statusLabel || "";

        const headerActions = document.createElement("div");
        headerActions.className = "message-header-actions";
        headerActions.appendChild(statusSpan);

        const body = document.createElement("div");
        body.className = "message-body";
        body.textContent = initialText || "";

        let copyBtn = null;
        if (role === "assistant") {
            copyBtn = document.createElement("button");
            copyBtn.type = "button";
            copyBtn.className = "btn-copy";
            copyBtn.classList.add("hidden");
            copyBtn.setAttribute("aria-label", "Copy assistant response");
            copyBtn.textContent = "COPY";
            copyBtn.addEventListener("click", function () {
                const textToCopy = body.textContent || "";
                if (!textToCopy) {
                    return;
                }
                const markCopied = function () {
                    copyBtn.textContent = "COPIED \u2713";
                    copyBtn.classList.add("copied");
                    const resetFn = function () {
                        copyBtn.textContent = "COPY";
                        copyBtn.classList.remove("copied");
                    };
                    if (window && typeof window.setTimeout === "function") {
                        window.setTimeout(resetFn, 1500);
                    }
                };
                if (
                    typeof navigator !== "undefined" &&
                    navigator &&
                    navigator.clipboard &&
                    typeof navigator.clipboard.writeText === "function"
                ) {
                    Promise.resolve(navigator.clipboard.writeText(textToCopy))
                        .then(markCopied)
                        .catch(function () {});
                }
            });
            headerActions.appendChild(copyBtn);
        }

        header.appendChild(roleSpan);
        header.appendChild(headerActions);

        const footer = document.createElement("div");
        footer.className = "message-footer hidden";
        footer.classList.add("hidden");

        card.appendChild(header);
        card.appendChild(body);
        card.appendChild(footer);

        messagesList.appendChild(card);
        scrollToBottom();

        return {
            card: card,
            statusSpan: statusSpan,
            copyBtn: copyBtn,
            body: body,
            footer: footer
        };
    }

    function revealCopyButtonIfNonEmpty(assistantUi) {
        if (assistantUi && assistantUi.copyBtn && assistantUi.body.textContent) {
            assistantUi.copyBtn.classList.remove("hidden");
        }
    }

    function populateTelemetryFooter(footerEl, meta) {
        while (footerEl.firstChild) {
            footerEl.removeChild(footerEl.firstChild);
        }
        const items = [];
        if (meta.finishReason) {
            items.push("FINISH: " + meta.finishReason);
        }
        if (meta.usage) {
            items.push(
                "TOKENS (IN/OUT/TOTAL): " +
                    meta.usage.inputTokens +
                    "/" +
                    meta.usage.outputTokens +
                    "/" +
                    meta.usage.totalTokens
            );
        }
        if (typeof meta.durationMs === "number") {
            items.push("TIME: " + meta.durationMs + " ms");
            if (meta.usage && meta.usage.outputTokens > 0 && meta.durationMs > 0) {
                const tokPerSec = ((meta.usage.outputTokens * 1000) / meta.durationMs).toFixed(1);
                items.push("SPEED: " + tokPerSec + " tok/s");
            }
        }
        if (typeof meta.evictedMessages === "number" && meta.evictedMessages > 0) {
            items.push("MESSAGES EVICTED: " + meta.evictedMessages);
        }

        if (items.length === 0) {
            footerEl.classList.add("hidden");
            return;
        }
        for (const text of items) {
            const pill = document.createElement("span");
            pill.textContent = text;
            footerEl.appendChild(pill);
        }
        footerEl.classList.remove("hidden");
    }

    function appendConversationMessagesIfCurrentEpoch(requestEpoch, userText, assistantText) {
        if (requestEpoch !== chatSessionEpoch) {
            return;
        }
        conversationHistory.push({ role: "user", content: userText });
        if (typeof assistantText === "string" && assistantText.length > 0) {
            conversationHistory.push({ role: "assistant", content: assistantText });
        }
        if (conversationHistory.length > maxHistoryMessagesLimit) {
            conversationHistory = conversationHistory.slice(
                conversationHistory.length - maxHistoryMessagesLimit
            );
        }
        updateMessageCounter();
    }

    async function fetchRuntimeStatus() {
        try {
            const response = await fetch("/api/status", {
                method: "GET",
                headers: { Accept: "application/json" }
            });
            const data = await response.json();

            metaBinding.textContent = (data.binding || "jni").toUpperCase() + " (v" + (data.runtimeVersion || "2.3.1") + ")";
            metaModel.textContent = data.modelName || "Not loaded";
            metaFormatter.textContent = data.chatFormatter || "auto";
            metaContext.textContent =
                (data.contextLength || 0).toLocaleString() + " / " + (data.maxOutputTokens || 0).toLocaleString() + " tok";
            metaBackends.textContent =
                Array.isArray(data.availableBackends) && data.availableBackends.length > 0
                    ? data.availableBackends.join(", ")
                    : "CPU";

            if (typeof data.maxPromptChars === "number" && data.maxPromptChars > 0) {
                maxPromptCharsLimit = data.maxPromptChars;
                updateCharCounter();
            }
            if (typeof data.maxHistoryMessages === "number" && data.maxHistoryMessages > 0) {
                maxHistoryMessagesLimit = data.maxHistoryMessages;
                updateMessageCounter();
            }
            if (typeof data.maxOutputTokens === "number" && data.maxOutputTokens > 0) {
                maxTokensInput.max = String(data.maxOutputTokens);
                if (Number(maxTokensInput.value) > data.maxOutputTokens) {
                    maxTokensInput.value = String(data.maxOutputTokens);
                }
            }

            if (!isGenerating) {
                if (data.chatEnabled && data.status === "ready") {
                    if (data.activeInferenceCount > 0) {
                        setBadgeState("status-busy", "BUSY (" + data.activeInferenceCount + " ACTIVE)");
                    } else {
                        setBadgeState("status-ready", "READY (" + (data.binding || "jni").toUpperCase() + ")");
                    }
                } else {
                    setBadgeState("status-error", "MODEL NOT READY");
                    if (data.errorMessage) {
                        showErrorBanner(data.errorCode || "model_not_ready", data.errorMessage);
                    }
                }
            }
        } catch (err) {
            if (!isGenerating) {
                setBadgeState("status-error", "SERVER UNREACHABLE");
            }
        }
    }

    function buildBoundedPayloadMessages(newUserText) {
        const combined = conversationHistory.concat([{ role: "user", content: newUserText }]);
        if (combined.length <= maxHistoryMessagesLimit) {
            return combined;
        }
        return combined.slice(combined.length - maxHistoryMessagesLimit);
    }

    async function runSynchronousChat(requestPayload, assistantUi, userText, requestEpoch, abortSignal) {
        const response = await fetch("/api/chat", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/json"
            },
            body: JSON.stringify(requestPayload),
            signal: abortSignal
        });

        const body = await response.json().catch(() => null);
        if (requestEpoch !== chatSessionEpoch) {
            return;
        }

        if (!response.ok) {
            const code = body && body.code ? body.code : "http_" + response.status;
            const msg = body && body.message ? body.message : "Request failed with status " + response.status;
            assistantUi.statusSpan.textContent = "FAILED";
            assistantUi.body.textContent = "[" + code + "] " + msg;
            showErrorBanner(code, msg);
            return;
        }

        const replyText = body && typeof body.reply === "string" ? body.reply : "";
        assistantUi.body.textContent = replyText;
        assistantUi.statusSpan.textContent = body.finishReason === "CANCELLED" ? "CANCELLED" : "COMPLETE";
        revealCopyButtonIfNonEmpty(assistantUi);
        populateTelemetryFooter(assistantUi.footer, body);

        appendConversationMessagesIfCurrentEpoch(requestEpoch, userText, replyText);
    }

    function parseSseFrames(buffer, onEvent) {
        let normalized = buffer.replace(/\r\n/g, "\n");
        let boundaryIndex;
        while ((boundaryIndex = normalized.indexOf("\n\n")) !== -1) {
            const rawFrame = normalized.slice(0, boundaryIndex);
            normalized = normalized.slice(boundaryIndex + 2);

            let eventName = "message";
            const dataLines = [];
            for (const line of rawFrame.split("\n")) {
                if (line.startsWith("event:")) {
                    eventName = line.slice(6).trim();
                } else if (line.startsWith("data:")) {
                    dataLines.push(line.slice(5).trimStart());
                }
            }
            if (dataLines.length > 0) {
                onEvent(eventName, dataLines.join("\n"));
            }
        }
        return normalized;
    }

    async function runStreamingChat(requestPayload, assistantUi, userText, requestEpoch, abortSignal) {
        const response = await fetch("/api/chat/stream", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "text/event-stream"
            },
            body: JSON.stringify(requestPayload),
            signal: abortSignal
        });

        if (requestEpoch !== chatSessionEpoch) {
            return;
        }

        if (!response.ok) {
            const errBody = await response.json().catch(() => null);
            if (requestEpoch !== chatSessionEpoch) {
                return;
            }
            const code = errBody && errBody.code ? errBody.code : "http_" + response.status;
            const msg =
                errBody && errBody.message ? errBody.message : "Streaming request failed (" + response.status + ")";
            assistantUi.statusSpan.textContent = "FAILED";
            assistantUi.body.textContent = "[" + code + "] " + msg;
            showErrorBanner(code, msg);
            return;
        }

        if (!response.body) {
            throw new Error("ReadableStream is not supported by this browser.");
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let sseBuffer = "";
        let streamedText = "";
        let completedNormally = false;

        const handleSseEvent = function (eventName, rawData) {
            if (requestEpoch !== chatSessionEpoch) {
                return;
            }
            let parsed;
            try {
                parsed = JSON.parse(rawData);
            } catch (e) {
                return;
            }

            if (eventName === "start") {
                assistantUi.statusSpan.textContent =
                    "STREAMING (PROMPT: " + parsed.promptTokens + " TOK)…";
            } else if (eventName === "token") {
                if (typeof parsed.text === "string") {
                    streamedText += parsed.text;
                    assistantUi.body.textContent = streamedText;
                    scrollToBottom();
                }
            } else if (eventName === "complete") {
                completedNormally = true;
                if (typeof parsed.text === "string" && parsed.text.length > 0 && streamedText.length === 0) {
                    streamedText = parsed.text;
                    assistantUi.body.textContent = streamedText;
                }
                assistantUi.statusSpan.textContent = parsed.cancelled ? "CANCELLED" : "COMPLETE";
                revealCopyButtonIfNonEmpty(assistantUi);
                populateTelemetryFooter(assistantUi.footer, parsed);

                appendConversationMessagesIfCurrentEpoch(requestEpoch, userText, streamedText);
                scrollToBottom();
            } else if (eventName === "error") {
                completedNormally = true;
                const code = parsed.code || "stream_error";
                const msg = parsed.message || "Streaming generation failed.";
                assistantUi.statusSpan.textContent = "ERROR";
                if (!streamedText) {
                    assistantUi.body.textContent = "[" + code + "] " + msg;
                }
                showErrorBanner(code, msg);
            }
        };

        while (true) {
            const { value, done } = await reader.read();
            if (requestEpoch !== chatSessionEpoch) {
                try {
                    reader.cancel();
                } catch (ignored) {}
                return;
            }
            if (done) {
                sseBuffer += decoder.decode();
                parseSseFrames(sseBuffer, handleSseEvent);
                break;
            }
            sseBuffer += decoder.decode(value, { stream: true });
            sseBuffer = parseSseFrames(sseBuffer, handleSseEvent);
        }

        if (
            requestEpoch === chatSessionEpoch &&
            !completedNormally &&
            assistantUi.statusSpan.textContent.indexOf("STREAMING") === 0
        ) {
            assistantUi.statusSpan.textContent = "STOPPED";
        }
    }

    async function handlePromptSubmit(event) {
        if (event) {
            event.preventDefault();
        }
        if (isGenerating) {
            return;
        }

        const userText = promptInput.value.trim();
        if (!userText) {
            showErrorBanner("empty_prompt", "Please enter a non-empty prompt before sending.");
            return;
        }
        if (userText.length > maxPromptCharsLimit) {
            showErrorBanner(
                "prompt_too_long",
                "Prompt length (" +
                    userText.length +
                    " chars) exceeds the configured maximum of " +
                    maxPromptCharsLimit +
                    " characters."
            );
            return;
        }

        clearErrorBanner();
        const requestEpoch = chatSessionEpoch;
        const requestId = generateRequestId();
        const payloadMessages = buildBoundedPayloadMessages(userText);
        const temperature = Number.parseFloat(temperatureInput.value);
        const maxOutputTokens = Number.parseInt(maxTokensInput.value, 10);

        const requestPayload = {
            requestId: requestId,
            messages: payloadMessages,
            temperature: Number.isFinite(temperature) ? temperature : 0.7,
            maxOutputTokens: Number.isFinite(maxOutputTokens) ? maxOutputTokens : 512
        };

        promptInput.value = "";
        updateCharCounter();

        createMessageCard("user", userText, "SENT");
        const assistantUi = createMessageCard("assistant", "", "WAITING FOR LOCAL MODEL…");

        const localAbortController = new AbortController();
        activeAbortController = localAbortController;
        activeRequestId = requestId;
        setGeneratingState(true);

        try {
            if (modeSelect.value === "sync") {
                await runSynchronousChat(
                    requestPayload,
                    assistantUi,
                    userText,
                    requestEpoch,
                    localAbortController.signal
                );
            } else {
                await runStreamingChat(
                    requestPayload,
                    assistantUi,
                    userText,
                    requestEpoch,
                    localAbortController.signal
                );
            }
        } catch (err) {
            if (requestEpoch !== chatSessionEpoch) {
                return;
            }
            if (err && err.name === "AbortError") {
                assistantUi.statusSpan.textContent = "CANCELLED";
                if (!assistantUi.body.textContent) {
                    assistantUi.body.textContent = "[Generation stopped by user]";
                } else {
                    revealCopyButtonIfNonEmpty(assistantUi);
                    appendConversationMessagesIfCurrentEpoch(
                        requestEpoch,
                        userText,
                        assistantUi.body.textContent
                    );
                }
            } else {
                assistantUi.statusSpan.textContent = "ERROR";
                showErrorBanner(
                    "network_error",
                    "Could not reach the local Spring Boot server. Verify that the application is running."
                );
            }
        } finally {
            if (requestEpoch === chatSessionEpoch) {
                setGeneratingState(false);
                fetchRuntimeStatus();
                promptInput.focus();
            }
        }
    }

    async function stopActiveGeneration() {
        if (!isGenerating) {
            return;
        }
        const reqId = activeRequestId;
        const controller = activeAbortController;

        // 1. Immediately abort the local browser fetch stream on the current tick
        if (controller) {
            controller.abort();
        }

        // 2. Signal server-side C++ ArgusAbortFlag (covers mid-prefill before first token)
        if (reqId) {
            try {
                await fetch("/api/chat/stop", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ requestId: reqId })
                });
            } catch (ignored) {
                // Ignore network errors during stop signal
            }
        }
    }

    function clearTranscript() {
        // Advance session epoch first so any in-flight or late callbacks from prior requests become no-ops
        chatSessionEpoch += 1;

        if (isGenerating) {
            stopActiveGeneration();
            setGeneratingState(false);
        }

        conversationHistory = [];
        while (messagesList.firstChild) {
            messagesList.removeChild(messagesList.firstChild);
        }
        clearErrorBanner();
        updateMessageCounter();
        fetchRuntimeStatus();
        promptInput.focus();
    }

    // Event Listeners
    chatForm.addEventListener("submit", handlePromptSubmit);
    stopBtn.addEventListener("click", stopActiveGeneration);
    newChatBtn.addEventListener("click", clearTranscript);
    refreshStatusBtn.addEventListener("click", fetchRuntimeStatus);
    alertDismissBtn.addEventListener("click", clearErrorBanner);
    promptInput.addEventListener("input", updateCharCounter);

    if (tabStream) {
        tabStream.addEventListener("click", function () {
            selectExecutionMode("stream");
        });
    }
    if (tabSync) {
        tabSync.addEventListener("click", function () {
            selectExecutionMode("sync");
        });
    }
    modeSelect.addEventListener("change", function () {
        selectExecutionMode(modeSelect.value);
    });

    promptInput.addEventListener("keydown", function (e) {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            handlePromptSubmit(e);
        }
    });

    document.querySelectorAll(".starter-chip").forEach(function (chip) {
        chip.addEventListener("click", function () {
            if (isGenerating) {
                return;
            }
            const sample = chip.getAttribute("data-prompt");
            if (sample) {
                promptInput.value = sample;
                updateCharCounter();
                promptInput.focus();
            }
        });
    });

    // Expose read/test seam for deterministic automated verification of epoch & transcript state
    window.__argusWorkbenchTestHooks = {
        getConversationHistory: function () {
            return conversationHistory.slice();
        },
        getChatSessionEpoch: function () {
            return chatSessionEpoch;
        },
        clearTranscript: clearTranscript,
        stopActiveGeneration: stopActiveGeneration,
        submitPrompt: handlePromptSubmit
    };

    // Initial bootstrap
    updateCharCounter();
    updateMessageCounter();
    fetchRuntimeStatus();
})();
