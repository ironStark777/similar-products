package com.alexmunoz.similarproducts.application;

import com.alexmunoz.similarproducts.application.SimilarProductsProperties.CatalogCalls;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Limits the calls to the product catalog in progress at the same time, so a burst of
 * requests cannot overload it.
 *
 * <p>A call waits for a free slot at most the permit timeout. If none frees up in time, it is
 * rejected with {@link CatalogCallRejectedException} and never started. A call interrupted
 * while it waits is cancelled and never started either. Slots are handed out in arrival
 * order.
 *
 * <p>Metrics: {@code catalog.calls.active} and {@code catalog.calls.active.peak}, the calls in
 * progress now and at most since startup, and {@code catalog.calls.permit.wait}, the time
 * spent waiting for a slot, tagged {@code outcome=acquired|rejected}.
 */
@Component
public class CatalogCallLimiter {

    private static final String PERMIT_WAIT = "catalog.calls.permit.wait";

    private final int maxConcurrent;
    private final Duration permitTimeout;
    private final Semaphore slots;
    private final AtomicInteger activeCalls = new AtomicInteger();
    private final AtomicInteger peakActiveCalls = new AtomicInteger();
    private final Timer acquiredWaits;
    private final Timer rejectedWaits;

    public CatalogCallLimiter(SimilarProductsProperties properties, MeterRegistry meterRegistry) {
        CatalogCalls catalogCalls = properties.catalogCalls();
        this.maxConcurrent = catalogCalls.maxConcurrent();
        this.permitTimeout = catalogCalls.permitTimeout();
        this.slots = new Semaphore(maxConcurrent, true);
        this.acquiredWaits = permitWaitTimer("acquired", meterRegistry);
        this.rejectedWaits = permitWaitTimer("rejected", meterRegistry);
        Gauge.builder("catalog.calls.active", activeCalls::get)
                .description("Calls to the product catalog in progress")
                .register(meterRegistry);
        Gauge.builder("catalog.calls.active.peak", peakActiveCalls::get)
                .description("Most calls to the product catalog in progress at the same time since startup")
                .register(meterRegistry);
    }

    /**
     * Runs a call to the catalog once a slot is free.
     *
     * @throws CatalogCallRejectedException if no slot frees up within the permit timeout
     * @throws CancellationException        if the thread is interrupted while it waits
     */
    public <T> T call(Supplier<T> call) {
        acquireSlot();
        int active = activeCalls.incrementAndGet();
        peakActiveCalls.accumulateAndGet(active, Math::max);
        try {
            return call.get();
        } finally {
            activeCalls.decrementAndGet();
            slots.release();
        }
    }

    private void acquireSlot() {
        long start = System.nanoTime();
        boolean acquired;
        try {
            acquired = slots.tryAcquire(permitTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting to call the product catalog");
        }
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        if (!acquired) {
            rejectedWaits.record(waited);
            throw new CatalogCallRejectedException("No free slot to call the product catalog within "
                    + permitTimeout + " (" + maxConcurrent + " calls in progress)");
        }
        acquiredWaits.record(waited);
    }

    private static Timer permitWaitTimer(String outcome, MeterRegistry meterRegistry) {
        return Timer.builder(PERMIT_WAIT)
                .description("Time waiting for a free slot to call the product catalog")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
