package com.alexmunoz.similarproducts.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs the lookups of one request in parallel, one virtual thread each, within the deadline of
 * the request.
 *
 * <p>{@link #join()} waits until every lookup has finished, one has failed or the deadline has
 * passed. On a failure or at the deadline, it requests the cancellation of the lookups still
 * running by interrupting them: one still waiting for a slot never starts, and one in progress
 * is aborted if it responds to the interrupt. Closing the scope waits for them to end only
 * until the deadline, so the response is not held by a lookup that does not respond; that
 * lookup keeps its slot until it really ends.
 *
 * <p>It follows the fork, join and close shape of the JDK structured concurrency, still a
 * preview feature in Java 21.
 */
final class LookupScope implements AutoCloseable {

    private static final ThreadFactory VIRTUAL_THREADS =
            Thread.ofVirtual().name("similar-products-lookup-", 0).factory();

    private final Deadline deadline;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(VIRTUAL_THREADS);
    private final List<Subtask<?>> subtasks = new ArrayList<>();
    private final CompletableFuture<Void> firstFailure = new CompletableFuture<>();

    LookupScope(Deadline deadline) {
        this.deadline = deadline;
    }

    /**
     * Starts a lookup.
     */
    <T> Subtask<T> fork(Supplier<T> lookup) {
        CompletableFuture<T> result = CompletableFuture.supplyAsync(lookup, executor);
        result.whenComplete((value, failure) -> {
            if (failure != null) {
                firstFailure.completeExceptionally(failure);
            }
        });
        Subtask<T> subtask = new Subtask<>(result);
        subtasks.add(subtask);
        return subtask;
    }

    /**
     * Waits until every lookup has finished, one has failed or the deadline has passed, and
     * takes the results of the lookups finished by then. At the deadline, it returns without
     * waiting any longer and the lookups still running are interrupted; a result that arrives
     * after that is not taken. On a failure, they are interrupted too and the failure is
     * rethrown as it was thrown.
     *
     * @throws CancellationException if the thread is interrupted while it waits
     */
    void join() {
        CompletableFuture<Void> allFinished = CompletableFuture.allOf(subtasks.stream()
                .map(subtask -> subtask.lookup)
                .toArray(CompletableFuture<?>[]::new));
        try {
            CompletableFuture.anyOf(allFinished, firstFailure).get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            subtasks.forEach(Subtask::takeResult);
        } catch (TimeoutException ex) {
            subtasks.forEach(Subtask::takeResult);
            executor.shutdownNow();
        } catch (ExecutionException ex) {
            executor.shutdownNow();
            throw rethrow(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new CancellationException("Interrupted while waiting for the lookups");
        }
    }

    /**
     * Interrupts the lookups still running, if any, and waits for them to end until the
     * deadline at most.
     */
    @Override
    public void close() {
        executor.shutdownNow();
        long timeLeft = deadline.remainingNanos();
        if (timeLeft > 0) {
            try {
                executor.awaitTermination(timeLeft, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new CompletionException(failure);
    }

    /**
     * A forked lookup.
     */
    static final class Subtask<T> {

        private final CompletableFuture<T> lookup;
        private Optional<T> result = Optional.empty();

        private Subtask(CompletableFuture<T> lookup) {
            this.lookup = lookup;
        }

        private void takeResult() {
            if (lookup.isDone() && !lookup.isCompletedExceptionally()) {
                result = Optional.of(lookup.join());
            }
        }

        /**
         * @return the result taken by {@link #join()}; empty if the lookup had not finished
         *         by then
         */
        Optional<T> result() {
            return result;
        }
    }
}
