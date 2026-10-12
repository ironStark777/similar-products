package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alexmunoz.similarproducts.application.SimilarProductsProperties.CatalogCalls;
import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SimilarProductsServiceTest {

    private static final ProductDetail SHIRT = product("1", "Shirt");
    private static final ProductDetail DRESS = product("2", "Dress");
    private static final ProductDetail BLAZER = product("3", "Blazer");

    private final FakeProductCatalog catalog = new FakeProductCatalog();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CatalogCallLimiter limiter = new CatalogCallLimiter(
            new SimilarProductsProperties(new CatalogCalls(10, Duration.ofMillis(100))), meterRegistry);

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
    void rethrowsTheFailureOfADetailLookupAsItWasThrown() {
        catalog.similarIds("10", "1", "2");
        catalog.product(SHIRT);
        catalog.detailLookup("2", () -> {
            throw new ProductCatalogException("Empty response for the detail of product 2");
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(ProductCatalogException.class);
    }

    @Test
    void interruptsTheOtherLookupsWhenOneFailsAndWaitsForThemToEnd() {
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
            throw new ProductCatalogException("Empty response for the detail of product 2");
        });

        assertThatThrownBy(() -> service().getSimilarProducts("10"))
                .isInstanceOf(ProductCatalogException.class);

        assertThat(slowLookupInterrupted).isTrue();
        assertThat(meterRegistry.get("catalog.calls.active").gauge().value()).isZero();
    }

    private SimilarProductsService service() {
        return new SimilarProductsService(catalog, limiter);
    }

    /**
     * Waits for the latch at most the timeout. An interrupt ends the wait and stays set on the
     * thread.
     */
    private static void awaitAtMost(CountDownLatch latch, Duration timeout) {
        try {
            latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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
