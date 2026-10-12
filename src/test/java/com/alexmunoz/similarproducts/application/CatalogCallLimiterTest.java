package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.alexmunoz.similarproducts.application.SimilarProductsProperties.CatalogCalls;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CatalogCallLimiterTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void runsTheCallWhenASlotIsFree() {
        CatalogCallLimiter limiter = limiter(1, Duration.ZERO);

        assertThat(limiter.call(() -> "result")).isEqualTo("result");
        assertThat(permitWaits("acquired").count()).isEqualTo(1);
        assertThat(activeCalls()).isZero();
        assertThat(pendingCalls()).isZero();
    }

    @Test
    void rejectsACallThatGetsNoSlotWithinThePermitTimeoutAndNeverStartsIt() throws InterruptedException {
        CatalogCallLimiter limiter = limiter(1, Duration.ofMillis(50));
        BlockedCall blocked = BlockedCall.start(limiter);
        AtomicBoolean started = new AtomicBoolean();

        assertThatThrownBy(() -> limiter.call(() -> started.getAndSet(true)))
                .isInstanceOf(CatalogCallRejectedException.class);
        blocked.release();

        assertThat(started).isFalse();
        assertThat(permitWaits("rejected").count()).isEqualTo(1);
        assertThat(permitWaits("rejected").totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(50);
        assertThat(pendingCalls()).isZero();
    }

    @Test
    void givesTheSlotToAWaitingCallWhenItFreesUp() throws InterruptedException {
        CatalogCallLimiter limiter = limiter(1, WAIT_LIMIT);
        BlockedCall blocked = BlockedCall.start(limiter);
        CompletableFuture<String> waitingCall = new CompletableFuture<>();

        Thread waiting = Thread.ofPlatform().daemon().start(() -> {
            try {
                waitingCall.complete(limiter.call(() -> "result"));
            } catch (RuntimeException ex) {
                waitingCall.completeExceptionally(ex);
            }
        });
        awaitWaitingForASlot(waiting);
        // A call waiting for a slot is pending, not active
        assertThat(pendingCalls()).isEqualTo(2);
        assertThat(activeCalls()).isEqualTo(1);
        blocked.release();

        assertThat(waitingCall).succeedsWithin(WAIT_LIMIT).isEqualTo("result");
        assertThat(permitWaits("rejected").count()).isZero();
        assertThat(pendingCalls()).isZero();
    }

    @Test
    void cancelsACallInterruptedWhileWaitingForASlotAndNeverStartsIt() throws InterruptedException {
        CatalogCallLimiter limiter = limiter(1, WAIT_LIMIT);
        BlockedCall blocked = BlockedCall.start(limiter);
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        Thread waiting = Thread.ofPlatform().daemon().start(() -> {
            try {
                limiter.call(() -> started.getAndSet(true));
            } catch (RuntimeException ex) {
                failure.set(ex);
            }
        });
        awaitWaitingForASlot(waiting);
        waiting.interrupt();
        waiting.join(WAIT_LIMIT.toMillis());
        blocked.release();

        assertThat(failure.get()).isInstanceOf(CancellationException.class);
        assertThat(started).isFalse();
        assertThat(permitWaits("rejected").count()).isZero();
        assertThat(pendingCalls()).isZero();
    }

    @Test
    void tracksTheCallsInProgressAndTheMostAtTheSameTime() throws InterruptedException {
        CatalogCallLimiter limiter = limiter(2, Duration.ZERO);
        BlockedCall first = BlockedCall.start(limiter);
        BlockedCall second = BlockedCall.start(limiter);

        assertThat(activeCalls()).isEqualTo(2);

        first.release();
        second.release();
        assertThat(activeCalls()).isZero();
        assertThat(meterRegistry.get("catalog.calls.active.peak").gauge().value()).isEqualTo(2);
    }

    @Test
    void freesTheSlotWhenTheCallFails() {
        CatalogCallLimiter limiter = limiter(1, Duration.ZERO);

        assertThatThrownBy(() -> limiter.call(() -> {
            throw new IllegalStateException("The catalog failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(limiter.call(() -> "result")).isEqualTo("result");
        assertThat(activeCalls()).isZero();
        assertThat(pendingCalls()).isZero();
    }

    private CatalogCallLimiter limiter(int maxConcurrent, Duration permitTimeout) {
        return new CatalogCallLimiter(
                new SimilarProductsProperties(new CatalogCalls(maxConcurrent, permitTimeout)), meterRegistry);
    }

    private Timer permitWaits(String outcome) {
        return meterRegistry.get("catalog.calls.permit.wait").tag("outcome", outcome).timer();
    }

    private double pendingCalls() {
        return meterRegistry.get("catalog.calls.pending").gauge().value();
    }

    private double activeCalls() {
        return meterRegistry.get("catalog.calls.active").gauge().value();
    }

    private static void awaitWaitingForASlot(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) {
                fail("The call did not start waiting for a slot");
            }
            Thread.sleep(1);
        }
    }

    /**
     * A call that holds a slot until it is released.
     */
    private static final class BlockedCall {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        private BlockedCall(CatalogCallLimiter limiter) {
            this.thread = Thread.ofPlatform().daemon().start(() -> limiter.call(() -> {
                started.countDown();
                awaitRelease();
                return null;
            }));
        }

        static BlockedCall start(CatalogCallLimiter limiter) throws InterruptedException {
            BlockedCall call = new BlockedCall(limiter);
            if (!call.started.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                fail("The blocked call did not start");
            }
            return call;
        }

        /**
         * Lets the call finish and waits until it has freed its slot.
         */
        void release() throws InterruptedException {
            release.countDown();
            thread.join(WAIT_LIMIT.toMillis());
        }

        private void awaitRelease() {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
