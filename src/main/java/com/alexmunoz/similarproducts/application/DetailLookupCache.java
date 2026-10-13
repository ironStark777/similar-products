package com.alexmunoz.similarproducts.application;

import com.alexmunoz.similarproducts.application.DetailLookup.Found;
import com.alexmunoz.similarproducts.application.DetailLookup.NotFound;
import com.alexmunoz.similarproducts.application.DetailLookup.Unavailable;
import com.alexmunoz.similarproducts.application.SimilarProductsProperties.DetailCache;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Shares and caches the lookups of product details, so concurrent and repeated requests for
 * the same product make a single call to the catalog.
 *
 * <p>The first consumer of a product starts its load on the executor of this cache, and those
 * that come while it is in progress wait for the same load. Each consumer waits within its own
 * deadline: running out of time, or being interrupted, ends its own wait and leaves the load
 * untouched, since others may be waiting for it. The load has the bounds of a call to the
 * catalog instead: the permit timeout of the limiter and the timeout of the HTTP client.
 *
 * <p>A finished lookup stays cached for a time that depends on its result, counted from the
 * end of the load and not extended by reads: {@code found-ttl} for a detail found and
 * {@code not-found-ttl} for a product that does not exist. An unavailable detail is not
 * cached, so the next consumer loads it again. Loads are shared within this instance and while
 * the entry stays in the cache: once it is evicted for size, the product is loaded again.
 */
final class DetailLookupCache implements AutoCloseable {

    private final AsyncCache<String, DetailLookup> lookups;
    private final ExecutorService loads;

    /**
     * @param clock source of time for the expiry of the entries
     * @param loads executor for the loads, closed with this cache
     */
    DetailLookupCache(DetailCache settings, NanoClock clock, ExecutorService loads) {
        this.loads = loads;
        this.lookups = Caffeine.newBuilder()
                .maximumSize(settings.maximumSize())
                // Computed when the load ends, and not changed by reads
                .expireAfter(Expiry.writing((String productId, DetailLookup lookup) -> timeToLive(lookup, settings)))
                .ticker(clock::nanoTime)
                .buildAsync();
    }

    /**
     * Returns the lookup of a product: cached, from the load in progress or from a new load.
     *
     * @param load looks up the product in the catalog, when there is nothing cached or in
     *             progress for it
     * @throws DeadlineExceededException if the deadline passes before the lookup is done; with
     *                                   the deadline already passed, no load is started
     * @throws CancellationException     if the thread is interrupted while it waits
     */
    DetailLookup lookUp(String productId, Deadline deadline, Function<String, DetailLookup> load) {
        long timeLeft = deadline.remainingNanos();
        if (timeLeft <= 0) {
            throw new DeadlineExceededException("No time left in the request to look up the detail of product " + productId);
        }
        CompletableFuture<DetailLookup> lookup = lookups.get(productId,
                (id, cacheExecutor) -> CompletableFuture.supplyAsync(() -> load.apply(id), loads));
        try {
            // A timed wait: giving up neither completes nor cancels the shared lookup
            return lookup.get(timeLeft, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw new DeadlineExceededException("No answer within the request budget for the detail of product " + productId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for the detail of product " + productId);
        } catch (ExecutionException ex) {
            throw rethrow(ex.getCause());
        }
    }

    /**
     * Waits for the loads in progress to end, each bounded by the permit timeout and the
     * timeout of the HTTP client.
     */
    @Override
    public void close() {
        loads.close();
    }

    private static Duration timeToLive(DetailLookup lookup, DetailCache settings) {
        return switch (lookup) {
            case Found found -> settings.foundTtl();
            case NotFound notFound -> settings.notFoundTtl();
            // It completes the future normally, so the cache would keep it unless told not to
            case Unavailable unavailable -> Duration.ZERO;
        };
    }

    /**
     * An unexpected failure of the load, such as a programming error, is rethrown as it was
     * thrown; the cache drops the failed entry.
     */
    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new CompletionException(failure);
    }
}
