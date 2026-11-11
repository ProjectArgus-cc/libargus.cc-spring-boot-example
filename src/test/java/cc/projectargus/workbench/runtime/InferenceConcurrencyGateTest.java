package cc.projectargus.workbench.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.projectargus.workbench.config.ArgusWorkbenchProperties;
import cc.projectargus.workbench.runtime.InferenceConcurrencyGate.PermitLease;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InferenceConcurrencyGateTest {

    @Test
    void enforcesSinglePermitAndClosesRejectedTokenImmediately() {
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setMaxConcurrentInference(1);
        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);

        CancellationToken token1 = CancellationToken.inMemory();
        CancellationToken rejectedToken = CancellationToken.inMemory();

        try (PermitLease lease1 = gate.acquireOrReject("req-1", token1)) {
            assertThat(gate.getActiveInferenceCount()).isEqualTo(1);
            assertThat(gate.getAvailablePermits()).isZero();

            assertThatThrownBy(() -> gate.acquireOrReject("req-2", rejectedToken))
                .isInstanceOf(InferenceCapacityExceededException.class)
                .hasMessageContaining("1 active generation(s)");

            // Rejected token must be closed automatically to prevent native handle leaks
            assertThat(rejectedToken.isClosed()).isTrue();

            // Active request can be cancelled out-of-band by requestId
            assertThat(gate.cancelRequest("req-1")).isTrue();
            assertThat(token1.isAborted()).isTrue();
        }

        // After lease1 closes, permit is restored and token1 is closed
        assertThat(token1.isClosed()).isTrue();
        assertThat(gate.getActiveInferenceCount()).isZero();
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
        assertThat(gate.cancelRequest("req-1")).isFalse();
    }

    @Test
    void permitLeaseCloseIsIdempotentAndNeverOverReleasesSemaphore() {
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setMaxConcurrentInference(1);
        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);

        CancellationToken token = CancellationToken.inMemory();
        PermitLease lease = gate.acquireOrReject("req-idempotent", token);

        lease.close();
        lease.close();
        lease.close();

        assertThat(gate.getAvailablePermits()).isEqualTo(1);
        assertThat(gate.getActiveInferenceCount()).isZero();
    }

    @Test
    void rejectsDuplicateActiveRequestIdWithoutOverwritingExistingTokenOrLeakingPermit() {
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setMaxConcurrentInference(2);
        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);

        CancellationToken token1 = CancellationToken.inMemory();
        CancellationToken duplicateToken = CancellationToken.inMemory();

        try (PermitLease lease1 = gate.acquireOrReject("dup-req-1", token1)) {
            assertThat(gate.getActiveInferenceCount()).isEqualTo(1);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);

            assertThatThrownBy(() -> gate.acquireOrReject("dup-req-1", duplicateToken))
                .isInstanceOf(DuplicateRequestIdException.class)
                .satisfies(ex -> assertThat(((DuplicateRequestIdException) ex).getRequestId()).isEqualTo("dup-req-1"));

            // Duplicate candidate token must be closed immediately and no semaphore permit may be consumed
            assertThat(duplicateToken.isClosed()).isTrue();
            assertThat(duplicateToken.isAborted()).isFalse();
            assertThat(gate.getActiveInferenceCount()).isEqualTo(1);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);

            // Original token1 must remain registered and cancellable via its requestId
            assertThat(token1.isAborted()).isFalse();
            assertThat(gate.cancelRequest("dup-req-1")).isTrue();
            assertThat(token1.isAborted()).isTrue();
        }

        // Once lease1 closes, the same requestId can be reused for a subsequent request
        assertThat(gate.getActiveInferenceCount()).isZero();
        assertThat(gate.getAvailablePermits()).isEqualTo(2);

        CancellationToken reusedToken = CancellationToken.inMemory();
        try (PermitLease reusedLease = gate.acquireOrReject("dup-req-1", reusedToken)) {
            assertThat(reusedLease.requestId()).isEqualTo("dup-req-1");
            assertThat(gate.getActiveInferenceCount()).isEqualTo(1);
        }
    }

    @Test
    void concurrentThreadsRacingWithIdenticalRequestIdAdmitExactlyOneAndRejectAllOthers() throws InterruptedException {
        int threads = 8;
        ArgusWorkbenchProperties props = new ArgusWorkbenchProperties();
        props.setMaxConcurrentInference(threads);
        InferenceConcurrencyGate gate = new InferenceConcurrencyGate(props);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger admittedCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        List<PermitLease> acquiredLeases = new ArrayList<>();
        List<CancellationToken> rejectedTokens = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                CancellationToken candidate = CancellationToken.inMemory();
                try {
                    startLatch.await();
                    PermitLease lease = gate.acquireOrReject("shared-race-id", candidate);
                    synchronized (acquiredLeases) {
                        acquiredLeases.add(lease);
                    }
                    admittedCount.incrementAndGet();
                } catch (DuplicateRequestIdException dup) {
                    synchronized (rejectedTokens) {
                        rejectedTokens.add(candidate);
                    }
                    duplicateCount.incrementAndGet();
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

        assertThat(admittedCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(threads - 1);
        assertThat(gate.getActiveInferenceCount()).isEqualTo(1);
        assertThat(gate.getAvailablePermits()).isEqualTo(threads - 1);
        assertThat(rejectedTokens).allMatch(CancellationToken::isClosed);

        for (PermitLease lease : acquiredLeases) {
            lease.close();
        }
        assertThat(gate.getActiveInferenceCount()).isZero();
        assertThat(gate.getAvailablePermits()).isEqualTo(threads);
    }
}
