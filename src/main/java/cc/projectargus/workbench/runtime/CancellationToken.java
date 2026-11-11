package cc.projectargus.workbench.runtime;

import cc.projectargus.libargus.ArgusAbortFlag;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe, idempotent cancellation handle bridging HTTP/SSE disconnect and explicit stop signals
 * to native {@link ArgusAbortFlag} cancellation without race conditions on close.
 *
 * <p>Async callbacks ({@code SseEmitter.onTimeout}, {@code onCompletion}, {@code onError}, or
 * {@code POST /api/chat/stop}) may invoke {@link #abort()} at any time—even concurrently with or slightly
 * after the inference worker thread invokes {@link #close()}.
 */
public final class CancellationToken implements AutoCloseable {

    private final ArgusAbortFlag nativeFlag;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private CancellationToken(ArgusAbortFlag nativeFlag) {
        this.nativeFlag = nativeFlag;
    }

    /**
     * Wraps a live native {@link ArgusAbortFlag}.
     */
    public static CancellationToken forNative(ArgusAbortFlag nativeFlag) {
        return new CancellationToken(Objects.requireNonNull(nativeFlag, "nativeFlag"));
    }

    /**
     * Creates a pure-Java in-memory token for model-independent unit tests.
     */
    public static CancellationToken inMemory() {
        return new CancellationToken(null);
    }

    /**
     * Requests cooperative cancellation. Safe to call from any thread at any point in the token's lifecycle;
     * becomes a safe no-op if the token has already been closed by the inference worker thread.
     */
    public void abort() {
        aborted.set(true);
        if (nativeFlag == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            if (!closed.get() && !nativeFlag.isClosed()) {
                nativeFlag.abort();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Returns {@code true} if cancellation has been requested.
     */
    public boolean isAborted() {
        if (aborted.get()) {
            return true;
        }
        if (nativeFlag == null) {
            return false;
        }
        lifecycleLock.lock();
        try {
            if (!closed.get() && !nativeFlag.isClosed()) {
                return nativeFlag.isAborted();
            }
            return aborted.get();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Returns the underlying {@link ArgusAbortFlag} for native downcalls, or {@code null} for in-memory test tokens.
     */
    public ArgusAbortFlag nativeFlag() {
        return nativeFlag;
    }

    /**
     * Returns {@code true} if this token has been closed.
     */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (nativeFlag == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            nativeFlag.close();
        } finally {
            lifecycleLock.unlock();
        }
    }
}
