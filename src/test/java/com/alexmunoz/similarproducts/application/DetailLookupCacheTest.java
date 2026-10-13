package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.alexmunoz.similarproducts.application.DetailLookup.Found;
import com.alexmunoz.similarproducts.application.DetailLookup.NotFound;
import com.alexmunoz.similarproducts.application.DetailLookup.Reason;
import com.alexmunoz.similarproducts.application.DetailLookup.Unavailable;
import com.alexmunoz.similarproducts.application.SimilarProductsProperties.DetailCache;
import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductCatalogTimeoutException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DetailLookupCacheTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);
    private static final DetailCache SETTINGS = new DetailCache(Duration.ofSeconds(30), Duration.ofSeconds(5), 100);
    private static final Found SHIRT = new Found(new ProductDetail("1", "Shirt", new BigDecimal("9.99"), true));

    private final FakeNanoClock clock = new FakeNanoClock();
    // A single thread, so a test can wait until the loads started so far have ended
    private final ExecutorService loads = Executors.newSingleThreadExecutor();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final DetailLookupCache cache = new DetailLookupCache(SETTINGS, clock, loads, meterRegistry);
    private final AtomicInteger loadCount = new AtomicInteger();
    private final CountDownLatch loadStarted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopTheLoads() {
        loads.shutdownNow();
    }

    @Test
    void sharesALoadInProgressBetweenConsumersOfTheSameProduct() throws InterruptedException {
        Waiter first = new Waiter(blockedLoad(SHIRT));
        awaitLatch(loadStarted);
        Waiter second = new Waiter(blockedLoad(SHIRT));
        second.awaitWaiting();

        release.countDown();

        assertThat(first.result()).isEqualTo(SHIRT);
        assertThat(second.result()).isEqualTo(SHIRT);
        assertThat(loadCount).hasValue(1);
    }

    @Test
    void aConsumerThatRunsOutOfTimeStopsWaitingWithoutCancellingTheLoad() throws InterruptedException {
        // The consumer that starts the load gives up on it
        assertThatThrownBy(() -> cache.lookUp("1", deadlineIn(Duration.ofMillis(50)), blockedLoad(SHIRT)))
                .isInstanceOf(DeadlineExceededException.class);
        Waiter other = new Waiter(blockedLoad(SHIRT));
        other.awaitWaiting();

        release.countDown();

        assertThat(other.result()).isEqualTo(SHIRT);
        assertThat(cache.lookUp("1", deadlineIn(WAIT_LIMIT), blockedLoad(SHIRT))).isEqualTo(SHIRT);
        assertThat(loadCount).hasValue(1);
    }

    @Test
    void anInterruptedConsumerStopsWaitingWithoutCancellingTheLoad() throws InterruptedException {
        Waiter interrupted = new Waiter(blockedLoad(SHIRT));
        interrupted.awaitWaiting();

        interrupted.thread.interrupt();

        assertThat(interrupted.failure()).isInstanceOf(CancellationException.class);
        release.countDown();
        assertThat(cache.lookUp("1", deadlineIn(WAIT_LIMIT), blockedLoad(SHIRT))).isEqualTo(SHIRT);
        assertThat(loadCount).hasValue(1);
    }

    @Test
    void keepsAFoundDetailFor30SecondsFromTheEndOfTheLoadWithoutExtendingItOnReads() throws Exception {
        assertExpiresAfter(SHIRT, Duration.ofSeconds(30));
    }

    @Test
    void keepsAProductThatDoesNotExistFor5SecondsFromTheEndOfTheLoadWithoutExtendingItOnReads() throws Exception {
        assertExpiresAfter(new NotFound(), Duration.ofSeconds(5));
    }

    @Test
    void loadsAnUnavailableDetailAgainOnTheNextLookUp() throws Exception {
        Unavailable unavailable = new Unavailable(Reason.TIMEOUT,
                new ProductCatalogTimeoutException("No complete answer for the detail of product 1", null));
        Function<String, DetailLookup> load = productId -> loadCount.incrementAndGet() == 1 ? unavailable : SHIRT;

        assertThat(cache.lookUp("1", deadlineIn(WAIT_LIMIT), load)).isEqualTo(unavailable);
        awaitLoadsEnded();

        assertThat(cache.lookUp("1", deadlineIn(WAIT_LIMIT), load)).isEqualTo(SHIRT);
        assertThat(loadCount).hasValue(2);
    }

    @Test
    void startsNoLoadForAConsumerWithNoTimeLeft() {
        assertThatThrownBy(() -> cache.lookUp("1", deadlineIn(Duration.ZERO), immediateLoad(SHIRT)))
                .isInstanceOf(DeadlineExceededException.class);

        assertThat(loadCount).hasValue(0);
    }

    @Test
    void countsALookUpAsAMissASharedLoadOrAHit() throws InterruptedException {
        Waiter first = new Waiter(blockedLoad(SHIRT));
        awaitLatch(loadStarted);
        Waiter second = new Waiter(blockedLoad(SHIRT));
        second.awaitWaiting();
        release.countDown();
        first.result();
        second.result();

        cache.lookUp("1", deadlineIn(WAIT_LIMIT), blockedLoad(SHIRT));

        assertThat(lookups("miss")).isEqualTo(1);
        assertThat(lookups("shared")).isEqualTo(1);
        assertThat(lookups("hit")).isEqualTo(1);
    }

    @Test
    void countsTheResultOfEachLoad() {
        cache.lookUp("1", deadlineIn(WAIT_LIMIT), immediateLoad(SHIRT));
        cache.lookUp("2", deadlineIn(WAIT_LIMIT), immediateLoad(new NotFound()));
        cache.lookUp("3", deadlineIn(WAIT_LIMIT), immediateLoad(unavailable(Reason.ERROR)));
        cache.lookUp("4", deadlineIn(WAIT_LIMIT), immediateLoad(unavailable(Reason.TIMEOUT)));
        cache.lookUp("5", deadlineIn(WAIT_LIMIT), immediateLoad(unavailable(Reason.SATURATED)));
        // A hit makes no load
        cache.lookUp("1", deadlineIn(WAIT_LIMIT), immediateLoad(SHIRT));

        assertThat(loads("found")).isEqualTo(1);
        assertThat(loads("not_found")).isEqualTo(1);
        assertThat(loads("error")).isEqualTo(1);
        assertThat(loads("timeout")).isEqualTo(1);
        assertThat(loads("saturated")).isEqualTo(1);
    }

    /**
     * The entry expires exactly after its time to live from the end of the load, although it
     * is read just before.
     */
    private void assertExpiresAfter(DetailLookup lookup, Duration timeToLive) throws Exception {
        cache.lookUp("1", deadlineIn(WAIT_LIMIT), immediateLoad(lookup));
        awaitLoadsEnded();

        clock.advance(timeToLive.minusSeconds(1));
        assertThat(cache.lookUp("1", deadlineIn(WAIT_LIMIT), immediateLoad(lookup))).isEqualTo(lookup);
        assertThat(loadCount).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        cache.lookUp("1", deadlineIn(WAIT_LIMIT), immediateLoad(lookup));
        assertThat(loadCount).hasValue(2);
    }

    private Function<String, DetailLookup> immediateLoad(DetailLookup lookup) {
        return productId -> {
            loadCount.incrementAndGet();
            return lookup;
        };
    }

    /**
     * A load that ends only once the test releases it.
     */
    private Function<String, DetailLookup> blockedLoad(DetailLookup lookup) {
        return productId -> {
            loadCount.incrementAndGet();
            loadStarted.countDown();
            awaitLatch(release);
            return lookup;
        };
    }

    /**
     * Waits until the loads started so far have ended, including the update of their entries,
     * which may happen after their consumers have been answered.
     */
    private void awaitLoadsEnded() throws Exception {
        loads.submit(() -> {
        }).get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private double lookups(String result) {
        return meterRegistry.get("detail.cache.lookups").tag("result", result).counter().count();
    }

    private double loads(String result) {
        return meterRegistry.get("detail.cache.loads").tag("result", result).counter().count();
    }

    private static Unavailable unavailable(Reason reason) {
        return new Unavailable(reason, new ProductCatalogException("The detail of the product could not be retrieved"));
    }

    private static Deadline deadlineIn(Duration budget) {
        return Deadline.after(budget, NanoClock.system());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("The latch did not open in time");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A consumer that looks up product 1 in a thread of its own.
     */
    private final class Waiter {

        private final AtomicReference<DetailLookup> lookup = new AtomicReference<>();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final Thread thread;

        private Waiter(Function<String, DetailLookup> load) {
            this.thread = Thread.ofPlatform().daemon().start(() -> {
                try {
                    lookup.set(cache.lookUp("1", deadlineIn(WAIT_LIMIT), load));
                } catch (RuntimeException ex) {
                    failure.set(ex);
                }
            });
        }

        /**
         * Waits until the consumer is waiting for the lookup.
         */
        void awaitWaiting() throws InterruptedException {
            long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
            while (thread.getState() != Thread.State.TIMED_WAITING) {
                if (System.nanoTime() > deadline) {
                    fail("The consumer did not start waiting for the lookup");
                }
                Thread.sleep(1);
            }
        }

        DetailLookup result() throws InterruptedException {
            awaitEnd();
            assertThat(failure.get()).isNull();
            return lookup.get();
        }

        RuntimeException failure() throws InterruptedException {
            awaitEnd();
            return failure.get();
        }

        private void awaitEnd() throws InterruptedException {
            thread.join(WAIT_LIMIT.toMillis());
            assertThat(thread.isAlive()).isFalse();
        }
    }
}
