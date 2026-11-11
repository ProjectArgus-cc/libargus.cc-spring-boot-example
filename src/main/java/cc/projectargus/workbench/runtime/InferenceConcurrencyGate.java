package cc.projectargus.workbench.runtime;

import cc.projectargus.workbench.config.ArgusWorkbenchProperties;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Bounded concurrency gate protecting local native inference from unbounded request queueing,
 * memory exhaustion, and active request-ID collisions.
 *
 * <p>Uses a fair {@link Semaphore} with zero-wait admission ({@code tryAcquire()}) and atomically
 * tracks active {@link CancellationToken} instances by unique {@code requestId} via
 * {@link ConcurrentHashMap#putIfAbsent(Object, Object)}.
 */
@Component
public class InferenceConcurrencyGate {

    private final int maxConcurrentInference;
    private final Semaphore semaphore;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final Map<String, CancellationToken> activeTokens = new ConcurrentHashMap<>();

    public InferenceConcurrencyGate(ArgusWorkbenchProperties properties) {
        this.maxConcurrentInference = Math.max(1, properties.getMaxConcurrentInference());
        this.semaphore = new Semaphore(this.maxConcurrentInference, true);
    }

    /**
     * Atomically registers {@code requestId} and attempts to acquire an inference execution permit
     * immediately without queueing.
     *
     * @param requestId         unique request identifier for cancellation lookup
     * @param cancellationToken request-scoped cooperative cancellation token
     * @return an idempotent {@link PermitLease} that releases the permit and closes the token on {@link PermitLease#close()}
     * @throws DuplicateRequestIdException        if {@code requestId} is already owned by an active request
     * @throws InferenceCapacityExceededException if all permits are currently occupied
     */
    public PermitLease acquireOrReject(String requestId, CancellationToken cancellationToken) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(cancellationToken, "cancellationToken");

        String normalizedId = requestId.trim();
        if (normalizedId.isEmpty()) {
            cancellationToken.close();
            throw new IllegalArgumentException("requestId must not be blank");
        }

        CancellationToken existing = activeTokens.putIfAbsent(normalizedId, cancellationToken);
        if (existing != null) {
            cancellationToken.close();
            throw new DuplicateRequestIdException(normalizedId);
        }

        if (!semaphore.tryAcquire()) {
            activeTokens.remove(normalizedId, cancellationToken);
            cancellationToken.close();
            throw new InferenceCapacityExceededException(maxConcurrentInference);
        }

        activeCount.incrementAndGet();
        return new PermitLease(normalizedId, cancellationToken);
    }

    /**
     * Signals cooperative cancellation for an active inference request by ID.
     *
     * @return {@code true} if an active request matched {@code requestId} and was signaled
     */
    public boolean cancelRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        CancellationToken token = activeTokens.get(requestId.trim());
        if (token == null) {
            return false;
        }
        token.abort();
        return true;
    }

    /**
     * Signals cooperative cancellation for all currently active inference requests.
     *
     * @return number of active requests signaled
     */
    public int cancelAllActive() {
        int signaled = 0;
        for (CancellationToken token : activeTokens.values()) {
            token.abort();
            signaled++;
        }
        return signaled;
    }

    public int getMaxConcurrentInference() {
        return maxConcurrentInference;
    }

    public int getActiveInferenceCount() {
        return activeCount.get();
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * Idempotent lease handle ensuring exactly-once permit release and {@link CancellationToken} closure.
     */
    public final class PermitLease implements AutoCloseable {

        private final String requestId;
        private final CancellationToken cancellationToken;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private PermitLease(String requestId, CancellationToken cancellationToken) {
            this.requestId = requestId;
            this.cancellationToken = cancellationToken;
        }

        public String requestId() {
            return requestId;
        }

        public CancellationToken cancellationToken() {
            return cancellationToken;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            try {
                activeTokens.remove(requestId, cancellationToken);
                cancellationToken.close();
            } finally {
                activeCount.decrementAndGet();
                semaphore.release();
            }
        }
    }
}
