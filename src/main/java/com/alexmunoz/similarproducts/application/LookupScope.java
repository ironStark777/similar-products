package com.alexmunoz.similarproducts.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * Runs the lookups of one request in parallel, one virtual thread each, and keeps them within
 * the request: as soon as one fails, the others are interrupted, so a lookup still waiting for
 * a slot never starts and one in progress is aborted. Closing the scope waits until all of
 * them have ended, so none outlives the request.
 *
 * <p>It follows the fork, join and close shape of the JDK structured concurrency, still a
 * preview feature in Java 21.
 */
final class LookupScope implements AutoCloseable {

    private static final ThreadFactory VIRTUAL_THREADS =
            Thread.ofVirtual().name("similar-products-lookup-", 0).factory();

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(VIRTUAL_THREADS);
    private final List<CompletableFuture<?>> lookups = new ArrayList<>();
    private final CompletableFuture<Void> firstFailure = new CompletableFuture<>();

    /**
     * Starts a lookup.
     *
     * @return its result, available once {@link #join()} has returned
     */
    <T> Supplier<T> fork(Supplier<T> lookup) {
        CompletableFuture<T> result = CompletableFuture.supplyAsync(lookup, executor);
        result.whenComplete((value, failure) -> {
            if (failure != null) {
                firstFailure.completeExceptionally(failure);
            }
        });
        lookups.add(result);
        return result::join;
    }

    /**
     * Waits until every lookup has finished or one has failed. On the first failure, the
     * other lookups are interrupted and the failure is rethrown as it was thrown.
     */
    void join() {
        CompletableFuture<Void> allFinished = CompletableFuture.allOf(lookups.toArray(CompletableFuture<?>[]::new));
        try {
            CompletableFuture.anyOf(allFinished, firstFailure).join();
        } catch (CompletionException ex) {
            executor.shutdownNow();
            throw ex.getCause() instanceof RuntimeException cause ? cause : ex;
        }
    }

    /**
     * Interrupts the lookups still running, if any, and waits until all of them have ended.
     */
    @Override
    public void close() {
        executor.shutdownNow();
        executor.close();
    }
}
