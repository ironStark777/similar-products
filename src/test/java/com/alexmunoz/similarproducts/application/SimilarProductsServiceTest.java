package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.alexmunoz.similarproducts.application.SimilarProductsProperties.CatalogCalls;
import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductCatalogTimeoutException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class SimilarProductsServiceTest {

    private static final ProductDetail SHIRT = product("1", "Shirt");
    private static final ProductDetail DRESS = product("2", "Dress");
    private static final ProductDetail BLAZER = product("3", "Blazer");

    /** Long enough not to interfere with the tests that are not about it. */
    private static final Duration DISTANT_BUDGET = Duration.ofMinutes(1);
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

    private final FakeProductCatalog catalog = new FakeProductCatalog();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CatalogCallLimiter limiter = new CatalogCallLimiter(properties(DISTANT_BUDGET), meterRegistry);

    @Test
    void returnsTheDetailOfEachSimilarProductInSimilarityOrder() {
        catalog.similarIds("10", "3", "1", "2");
        catalog.product(SHIRT);
        catalog.product(DRESS);
        catalog.product(BLAZER);

        assertThat(service().getSimilarProducts("10")).containsExactly(BLAZER, SHIRT, DRESS);
    }

    @Test
    void leavesOutSimilarProductsThatDoNotExist() {
        catalog.similarIds("10", "1", "404", "2");
        catalog.product(SHIRT);
        catalog.product(DRESS);

        assertThat(service().getSimilarProducts("10")).containsExactly(SHIRT, DRESS);
    }

    @Test
    void returnsAnEmptyListWhenThereAreNoSimilarProducts() {
        catalog.similarIds("10");

        assertThat(service().getSimilarProducts("10")).isEmpty();
    }

    @Test
    void returnsAnEmptyListWhenNoSimilarProductExists() {
        catalog.similarIds("10", "404", "405");

        assertThat(service().getSimilarProducts("10")).isEmpty();
    }

    @Test
    void doesNotRepeatProductsWhenSimilarIdsContainDuplicates() {
        catalog.similarIds("10", "1", "2", "1");
        catalog.product(SHIRT);
        catalog.product(DRESS);

        assertThat(service().getSimilarProducts("10")).containsExactly(SHIRT, DRESS);
    }

    @Test
    void failsWhenTheProductDoesNotExist() {
        assertThatThrownBy(() -> service().getSimilarProducts("404"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void looksUpTheDetailsInParallel() {
        CyclicBarrier allLookupsInProgress = new CyclicBarrier(3);
        catalog.similarIds("10", "1", "2", "3");
        for (ProductDetail product : List.of(SHIRT, DRESS, BLAZER)) {
            catalog.detailLookup(product.id(), () -> {
                await(allLookupsInProgress);
                return Optional.of(product);
            });
        }

        assertThat(service().getSimilarProducts("10")).containsExactly(SHIRT, DRESS, BLAZER);
    }

    @Test
    void makesEveryCallToTheCatalogThroughTheLimiter() {
        catalog.similarIds("10", "1", "2", "1");
        catalog.product(SHIRT);
        catalog.product(DRESS);

        service().getSimilarProducts("10");

        // The similar ids, plus one detail per distinct product
        assertThat(meterRegistry.get("catalog.calls.permit.wait").tag("outcome", "acquired").timer().count())
                .isEqualTo(3);
    }

    @Test
    void leavesOutSimilarProductsThatFailTimeOutOrAreRejected() {
        catalog.similarIds("10", "1", "2", "3", "4");
        catalog.product(SHIRT);
        catalog.detailLookup("2", () -> {
            throw timeout("2");
        });
        catalog.detailLookup("3", () -> {
            throw failure("3");
        });
        catalog.detailLookup("4", () -> {
            throw rejected();
        });

        assertThat(service().getSimilarProducts("10")).containsExactly(SHIRT);
    }

    @Test
    void failsWithACatalogFailureFirstWhenNoSimilarProductCouldBeRetrieved() {
        catalog.similarIds("10", "1", "2", "3", "404");
        catalog.detailLookup("1", () -> {
            throw timeout("1");
        });
        catalog.detailLookup("2", () -> {
            throw failure("2");
        });
        catalog.detailLookup("3", () -> {
            throw rejected();
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void failsWithATimeoutBeforeARejectionWhenNoSimilarProductCouldBeRetrieved() {
        catalog.similarIds("10", "1", "2", "404");
        catalog.detailLookup("1", () -> {
            throw rejected();
        });
        catalog.detailLookup("2", () -> {
            throw timeout("2");
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(ProductCatalogTimeoutException.class);
    }

    @Test
    void failsWithARejectionWhenEveryUnavailableProductWasRejected() {
        catalog.similarIds("10", "1", "404");
        catalog.detailLookup("1", () -> {
            throw rejected();
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(CatalogCallRejectedException.class);
    }

    @Test
    void propagatesTheFailureOfTheSimilarIds() {
        catalog.similarIdsLookup("10", () -> {
            throw new ProductCatalogTimeoutException("No complete answer for the similar ids of product 10", null);
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(ProductCatalogTimeoutException.class);
    }

    @Test
    void propagatesAnUnexpectedFailureOfADetailLookup() {
        catalog.similarIds("10", "1", "2");
        catalog.product(SHIRT);
        catalog.detailLookup("2", () -> {
            throw new IllegalStateException("A programming error");
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNotTurnACancellationIntoAnUnavailableProduct() {
        catalog.similarIds("10", "1", "2");
        catalog.product(SHIRT);
        catalog.detailLookup("2", () -> {
            throw new CancellationException("Interrupted while waiting for the detail of product 2");
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void interruptsTheOtherLookupsOnAnUnexpectedFailureAndWaitsForThemToEnd() {
        CountDownLatch slowLookupInProgress = new CountDownLatch(1);
        AtomicBoolean slowLookupInterrupted = new AtomicBoolean();
        catalog.similarIds("10", "1", "2");
        catalog.detailLookup("1", () -> {
            slowLookupInProgress.countDown();
            // Never opens: only an interrupt ends the wait early
            awaitAtMost(new CountDownLatch(1), Duration.ofSeconds(5));
            slowLookupInterrupted.set(Thread.currentThread().isInterrupted());
            return Optional.of(SHIRT);
        });
        catalog.detailLookup("2", () -> {
            awaitAtMost(slowLookupInProgress, Duration.ofSeconds(5));
            throw new IllegalStateException("A programming error");
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(slowLookupInterrupted).isTrue();
        assertThat(meterRegistry.get("catalog.calls.active").gauge().value()).isZero();
    }

    private static ProductCatalogTimeoutException timeout(String productId) {
        return new ProductCatalogTimeoutException("No complete answer for the detail of product " + productId, null);
    }

    private static ProductCatalogException failure(String productId) {
        return new ProductCatalogException("The existing APIs answered 500 for the detail of product " + productId);
    }

    private static CatalogCallRejectedException rejected() {
        return new CatalogCallRejectedException("No free slot to call the product catalog");
    }

    @Test
    void answersWithTheDetailsFoundWhenTheBudgetRunsOutWithoutWaitingForTheOthers() {
        CountDownLatch release = new CountDownLatch(1);
        catalog.similarIds("10", "1", "2");
        catalog.product(SHIRT);
        // Ignores the interrupt: only the test ends it
        catalog.detailLookup("2", () -> {
            awaitIgnoringInterrupts(release);
            return Optional.of(DRESS);
        });
        long start = System.nanoTime();

        List<ProductDetail> similarProducts = service(Duration.ofMillis(300)).getSimilarProducts("10");

        assertThat(similarProducts).containsExactly(SHIRT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(WAIT_LIMIT);
        // The lookup still running keeps its slot until it really ends
        assertThat(gauge("catalog.calls.active")).isEqualTo(1);
        release.countDown();
        awaitNoPendingCalls();
    }

    @Test
    void interruptsTheLookupsStillRunningWhenTheBudgetRunsOut() {
        CountDownLatch interrupted = new CountDownLatch(1);
        catalog.similarIds("10", "1", "2");
        catalog.product(SHIRT);
        catalog.detailLookup("2", () -> {
            // Never opens: only an interrupt ends the wait early
            awaitAtMost(new CountDownLatch(1), WAIT_LIMIT.multipliedBy(2));
            if (Thread.currentThread().isInterrupted()) {
                interrupted.countDown();
            }
            return Optional.of(DRESS);
        });

        assertThat(service(Duration.ofMillis(300)).getSimilarProducts("10")).containsExactly(SHIRT);

        assertThat(awaitAtMost(interrupted, WAIT_LIMIT)).isTrue();
        awaitNoPendingCalls();
    }

    @Test
    void stopsWaitingForTheSimilarIdsAtTheDeadline() {
        catalog.similarIdsLookup("10", () -> {
            awaitAtMost(new CountDownLatch(1), WAIT_LIMIT.multipliedBy(2));
            return List.of("1");
        });
        long start = System.nanoTime();

        assertThatThrownBy(() -> service(Duration.ofMillis(300)).getSimilarProducts("10"))
                .isInstanceOf(DeadlineExceededException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(WAIT_LIMIT);
        awaitNoPendingCalls();
    }

    @Test
    void failsWithATimeoutWhenNoDetailArrivesWithinTheBudget() {
        catalog.similarIds("10", "1");
        catalog.detailLookup("1", () -> {
            awaitAtMost(new CountDownLatch(1), WAIT_LIMIT.multipliedBy(2));
            return Optional.of(SHIRT);
        });

        assertThatThrownBy(() -> service(Duration.ofMillis(300)).getSimilarProducts("10"))
                .isInstanceOf(DeadlineExceededException.class);
    }

    @Test
    void doesNotLookUpTheDetailsWhenTheSimilarIdsUseUpTheBudget() {
        FakeNanoClock clock = new FakeNanoClock();
        AtomicBoolean detailLookedUp = new AtomicBoolean();
        catalog.similarIdsLookup("10", () -> {
            clock.advance(Duration.ofSeconds(3));
            return List.of("1");
        });
        catalog.detailLookup("1", () -> {
            detailLookedUp.set(true);
            return Optional.of(SHIRT);
        });
        SimilarProductsService service = new SimilarProductsService(catalog, limiter, properties(Duration.ofSeconds(2)), clock);

        assertThatThrownBy(() -> service.getSimilarProducts("10"))
                .isInstanceOf(DeadlineExceededException.class);

        assertThat(detailLookedUp).isFalse();
    }

    private SimilarProductsService service() {
        return service(DISTANT_BUDGET);
    }

    private SimilarProductsService service(Duration requestBudget) {
        return new SimilarProductsService(catalog, limiter, properties(requestBudget), NanoClock.system());
    }

    private static SimilarProductsProperties properties(Duration requestBudget) {
        return new SimilarProductsProperties(requestBudget, new CatalogCalls(10, Duration.ofMillis(100)));
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    /**
     * Waits until every call has left the limiter: the lookups left behind end on their own.
     */
    private void awaitNoPendingCalls() {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (gauge("catalog.calls.pending") != 0) {
            if (System.nanoTime() > deadline) {
                fail("Calls to the catalog still pending");
            }
            LockSupport.parkNanos(1_000_000);
        }
    }

    /**
     * Waits for the latch, going on waiting when interrupted; the interrupt stays set.
     */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + WAIT_LIMIT.multipliedBy(2).toNanos();
        while (latch.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                latch.await(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for the latch at most the timeout. An interrupt ends the wait and stays set on the
     * thread.
     *
     * @return whether the latch opened
     */
    private static boolean awaitAtMost(CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Lets a lookup go on only once all of them are in progress: done one after another, the
     * first one would wait until it times out.
     */
    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (BrokenBarrierException | TimeoutException ex) {
            throw new IllegalStateException("The lookups did not run in parallel", ex);
        }
    }

    private static ProductDetail product(String id, String name) {
        return new ProductDetail(id, name, new BigDecimal("9.99"), true);
    }
}
