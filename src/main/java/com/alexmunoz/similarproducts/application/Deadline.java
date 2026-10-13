package com.alexmunoz.similarproducts.application;

import java.time.Duration;

/**
 * The point by which the processing of a request must be done, measured on a
 * {@link NanoClock}. It bounds the work of the request, not the moment the client receives
 * the response: scheduling, serialization and transport come on top.
 */
public final class Deadline {

    /** On a clock that stands still, so the time left never shrinks. */
    private static final Deadline NONE = new Deadline(() -> 0L, Long.MAX_VALUE);

    private final NanoClock clock;
    private final long deadline;

    private Deadline(NanoClock clock, long deadline) {
        this.clock = clock;
        this.deadline = deadline;
    }

    public static Deadline after(Duration budget, NanoClock clock) {
        return new Deadline(clock, clock.nanoTime() + budget.toNanos());
    }

    /**
     * A deadline that never passes, for work that belongs to no request, such as a load shared
     * by several of them.
     */
    public static Deadline none() {
        return NONE;
    }

    /**
     * @return the time left, zero or negative once the deadline has passed
     */
    public long remainingNanos() {
        return deadline - clock.nanoTime();
    }

    public boolean hasPassed() {
        return remainingNanos() <= 0;
    }
}
